#!/usr/bin/env bash
set -euo pipefail

deploy_root="${1:?deploy root is required}"
environment="${2:?environment is required}"
image_tag="${3:?image tag is required}"
bundle_path="${4:?bundle path is required}"

release_dir="${deploy_root}/releases/${image_tag}"
shared_dir="${deploy_root}/shared"
env_file="${shared_dir}/${environment}.env"
caddyfile_path="${shared_dir}/Caddyfile"
compose_file="${release_dir}/deploy/compose/stack.compose.yml"
project_name="xrag-${environment}"

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

mkdir -p "${release_dir}" "${shared_dir}"
rm -rf "${release_dir:?}"/*
tar -xzf "${bundle_path}" -C "${release_dir}"

cp "${release_dir}/deploy/caddy/Caddyfile" "${caddyfile_path}"

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
docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" up -d api worker web caddy
docker_run compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" ps

rm -f "${bundle_path}"
docker_run image prune -f >/dev/null 2>&1 || true
