#!/usr/bin/env bash
set -euo pipefail

deploy_root="${1:?deploy root is required}"
environment="${2:?environment is required}"
image_tag="${3:?image tag is required}"
bundle_path="${4:?bundle path is required}"

release_dir="${deploy_root}/releases/${image_tag}"
shared_dir="${deploy_root}/shared"
shared_bin_dir="${shared_dir}/bin"
shared_systemd_dir="${shared_dir}/systemd"
env_file="${shared_dir}/${environment}.env"
caddyfile_path="${shared_dir}/Caddyfile"
compose_file="${release_dir}/deploy/compose/stack.compose.yml"
project_name="xrag-${environment}"
disk_guard_path="${shared_bin_dir}/xrag-disk-guard.sh"

docker_cmd=(docker)

resolve_docker_access() {
  if docker info >/dev/null 2>&1; then
    docker_cmd=(docker)
    return 0
  fi

  if sudo -n docker info >/dev/null 2>&1; then
    docker_cmd=(sudo -n docker)
    return 0
  fi

  cat >&2 <<EOF
Docker daemon is not accessible for the current remote user.
Fix one of these on the server:
- add the deploy user to the docker group, then re-login
- allow passwordless sudo for docker commands
EOF
  return 1
}

docker_run() {
  "${docker_cmd[@]}" "$@"
}

read_env_file_key() {
  local key="${1:?env key is required}"
  grep -E "^${key}=" "${env_file}" | tail -n 1 | cut -d= -f2-
}

verify_service_image() {
  local service_name="${1:?service name is required}"
  local expected_image="${2:?expected image is required}"
  local container_id
  local actual_image

  container_id="$(
    docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" ps -q "${service_name}"
  )"

  if [[ -z "${container_id}" ]]; then
    echo "Service ${service_name} is not running after deploy." >&2
    return 1
  fi

  actual_image="$(docker_run inspect "${container_id}" --format '{{.Config.Image}}')"

  if [[ "${actual_image}" != "${expected_image}" ]]; then
    cat >&2 <<EOF
Service ${service_name} is running an unexpected image.
Expected: ${expected_image}
Actual:   ${actual_image}
EOF
    return 1
  fi
}

docker_login_with_retry() {
  local attempts=5
  local attempt
  for attempt in $(seq 1 "${attempts}"); do
    if printf '%s' "${REGISTRY_PASSWORD}" | docker_run login "${REGISTRY_HOST}" -u "${REGISTRY_USERNAME}" --password-stdin; then
      return 0
    fi

    if [[ "${attempt}" -eq "${attempts}" ]]; then
      echo "Registry login failed after ${attempts} attempts." >&2
      return 1
    fi

    local sleep_seconds=$((attempt * 5))
    echo "Registry login failed on attempt ${attempt}/${attempts}, retrying in ${sleep_seconds}s..." >&2
    sleep "${sleep_seconds}"
  done
}

mkdir -p "${release_dir}" "${shared_dir}" "${shared_bin_dir}" "${shared_systemd_dir}"
rm -rf "${release_dir:?}"/*
tar -xzf "${bundle_path}" -C "${release_dir}"

cp "${release_dir}/deploy/caddy/Caddyfile" "${caddyfile_path}"
cp "${release_dir}/deploy/scripts/disk-guard.sh" "${disk_guard_path}"
chmod 755 "${disk_guard_path}"
cp "${release_dir}/deploy/systemd/xrag-disk-guard.service" "${shared_systemd_dir}/xrag-disk-guard.service"
cp "${release_dir}/deploy/systemd/xrag-disk-guard.timer" "${shared_systemd_dir}/xrag-disk-guard.timer"

if [[ -n "${XRAG_ENV_FILE_B64:-}" ]]; then
  printf '%s' "${XRAG_ENV_FILE_B64}" | base64 --decode > "${env_file}"
  chmod 600 "${env_file}"
fi

if [[ ! -f "${env_file}" ]]; then
  echo "Missing deployment env file: ${env_file}" >&2
  exit 1
fi

if [[ -z "${REGISTRY_HOST:-}" || -z "${REGISTRY_USERNAME:-}" || -z "${REGISTRY_PASSWORD:-}" ]]; then
  echo "Missing registry credentials" >&2
  exit 1
fi

resolve_docker_access

XRAG_DISK_WARN_PERCENT="$(read_env_file_key XRAG_DISK_WARN_PERCENT || true)" \
XRAG_DISK_PRUNE_PERCENT="$(read_env_file_key XRAG_DISK_PRUNE_PERCENT || true)" \
XRAG_DISK_FAIL_PERCENT="$(read_env_file_key XRAG_DISK_FAIL_PERCENT || true)" \
XRAG_KEEP_RELEASES="$(read_env_file_key XRAG_KEEP_RELEASES || true)" \
XRAG_DOCKER_LOG_TRUNCATE_MB="$(read_env_file_key XRAG_DOCKER_LOG_TRUNCATE_MB || true)" \
"${disk_guard_path}" "${deploy_root}"

docker_login_with_retry

docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" down --remove-orphans || true

export XRAG_CADDYFILE_PATH="${caddyfile_path}"
docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" pull
docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" up -d postgres redis minio
docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" exec -T postgres sh -lc '
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d template1 -tAc "select 1 from pg_database where datname='\''$POSTGRES_DB'\''" | grep -q 1 \
    || psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d template1 -c "create database \"$POSTGRES_DB\" owner \"$POSTGRES_USER\""
'
docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" run --rm -T api-migrate </dev/null
docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" up -d --force-recreate api worker web caddy
verify_service_image api "${XRAG_API_IMAGE}"
verify_service_image worker "${XRAG_WORKER_IMAGE}"
verify_service_image web "${XRAG_WEB_IMAGE}"
docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" ps

rm -f "${bundle_path}"
docker_run image prune -f >/dev/null 2>&1 || true
