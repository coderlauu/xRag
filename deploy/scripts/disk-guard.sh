#!/usr/bin/env bash
set -euo pipefail

deploy_root="${1:?deploy root is required}"

warn_percent="${XRAG_DISK_WARN_PERCENT:-70}"
prune_percent="${XRAG_DISK_PRUNE_PERCENT:-80}"
fail_percent="${XRAG_DISK_FAIL_PERCENT:-95}"
keep_releases="${XRAG_KEEP_RELEASES:-5}"
log_truncate_mb="${XRAG_DOCKER_LOG_TRUNCATE_MB:-200}"
docker_prune_enabled="${XRAG_DOCKER_PRUNE_ENABLED:-1}"
shared_tmp_dir="${deploy_root}/shared/tmp"
releases_dir="${deploy_root}/releases"

docker_cmd=(docker)
docker_available=0

timestamp() {
  date '+%F %T'
}

log() {
  printf '[xrag-disk-guard] %s %s\n' "$(timestamp)" "$*"
}

usage_percent() {
  df -P "${deploy_root}" | awk 'NR == 2 { gsub("%", "", $5); print $5 }'
}

resolve_docker_access() {
  if docker info >/dev/null 2>&1; then
    docker_cmd=(docker)
    docker_available=1
    return 0
  fi

  if sudo -n docker info >/dev/null 2>&1; then
    docker_cmd=(sudo -n docker)
    docker_available=1
    return 0
  fi

  docker_available=0
  return 1
}

docker_run() {
  "${docker_cmd[@]}" "$@"
}

cleanup_shared_tmp() {
  mkdir -p "${shared_tmp_dir}"
  find "${shared_tmp_dir}" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  log "已清理 shared/tmp。"
}

trim_releases() {
  local release_count
  mkdir -p "${releases_dir}"
  release_count="$(find "${releases_dir}" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"

  if [[ "${release_count}" -le "${keep_releases}" ]]; then
    log "release 数量 ${release_count}，无需裁剪。"
    return 0
  fi

  find "${releases_dir}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' \
    | sort \
    | head -n "-${keep_releases}" \
    | while read -r release_id; do
        [[ -z "${release_id}" ]] && continue
        rm -rf "${releases_dir}/${release_id}"
        log "已删除旧 release: ${release_id}"
      done
}

truncate_large_container_logs() {
  if [[ "${log_truncate_mb}" -le 0 ]]; then
    log "已禁用 Docker 日志截断。"
    return 0
  fi

  if [[ ! -d /var/lib/docker/containers ]]; then
    log "未发现 Docker container 日志目录，跳过日志截断。"
    return 0
  fi

  find /var/lib/docker/containers -name '*-json.log' -size +"${log_truncate_mb}"M -print \
    | while read -r log_file; do
        [[ -z "${log_file}" ]] && continue
        : > "${log_file}"
        log "已截断过大容器日志: ${log_file}"
      done
}

prune_docker_artifacts() {
  if [[ "${docker_prune_enabled}" != "1" ]]; then
    log "已禁用 Docker prune。"
    return 0
  fi

  if ! resolve_docker_access; then
    log "当前远端用户无 Docker 访问权限，跳过 Docker prune。"
    return 0
  fi

  docker_run container prune -f >/dev/null 2>&1 || true
  docker_run image prune -af >/dev/null 2>&1 || true
  docker_run builder prune -af >/dev/null 2>&1 || true
  log "已执行 Docker container/image/builder prune。"
}

validate_thresholds() {
  if [[ "${keep_releases}" -lt 1 ]]; then
    echo "XRAG_KEEP_RELEASES must be >= 1" >&2
    exit 1
  fi

  if [[ "${warn_percent}" -ge "${fail_percent}" ]]; then
    echo "XRAG_DISK_WARN_PERCENT must be < XRAG_DISK_FAIL_PERCENT" >&2
    exit 1
  fi

  if [[ "${prune_percent}" -gt "${fail_percent}" ]]; then
    echo "XRAG_DISK_PRUNE_PERCENT must be <= XRAG_DISK_FAIL_PERCENT" >&2
    exit 1
  fi
}

validate_thresholds

before_usage="$(usage_percent)"
log "开始磁盘守护，deploy_root=${deploy_root}，当前使用率=${before_usage}%"

cleanup_shared_tmp
trim_releases

after_basic_usage="$(usage_percent)"
log "基础清理后使用率=${after_basic_usage}%"

if [[ "${after_basic_usage}" -ge "${prune_percent}" ]]; then
  log "磁盘使用率达到 prune 阈值 ${prune_percent}% ，开始执行强化清理。"
  truncate_large_container_logs
  prune_docker_artifacts
fi

final_usage="$(usage_percent)"
log "磁盘守护完成，最终使用率=${final_usage}%"

if [[ "${final_usage}" -ge "${fail_percent}" ]]; then
  cat >&2 <<EOF
磁盘使用率仍达到 ${final_usage}% ，超过 fail 阈值 ${fail_percent}% 。
已中止后续 deploy，请先人工检查：
- ${shared_tmp_dir}
- ${releases_dir}
- /var/lib/docker
EOF
  exit 1
fi

if [[ "${final_usage}" -ge "${warn_percent}" ]]; then
  log "磁盘使用率仍高于 warn 阈值 ${warn_percent}% ，建议继续检查 Docker 镜像、日志和 release 保留策略。"
fi
