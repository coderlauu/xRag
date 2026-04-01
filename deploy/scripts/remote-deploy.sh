#!/usr/bin/env bash
set -euo pipefail

deploy_root="${1:?deploy root is required}"
environment="${2:?environment is required}"
image_tag="${3:?image tag is required}"
bundle_path="${4:?bundle path is required}"

release_dir="${deploy_root}/releases/${image_tag}"
shared_dir="${deploy_root}/shared"
env_file="${shared_dir}/${environment}.env"
compose_file="${release_dir}/deploy/compose/stack.compose.yml"
project_name="xrag-${environment}"

mkdir -p "${release_dir}" "${shared_dir}"
rm -rf "${release_dir:?}"/*
tar -xzf "${bundle_path}" -C "${release_dir}"

if [[ -n "${XRAG_ENV_FILE_B64:-}" ]]; then
  printf '%s' "${XRAG_ENV_FILE_B64}" | base64 --decode > "${env_file}"
  chmod 600 "${env_file}"
fi

if [[ ! -f "${env_file}" ]]; then
  echo "Missing deployment env file: ${env_file}" >&2
  exit 1
fi

if [[ -z "${GHCR_USERNAME:-}" || -z "${GHCR_TOKEN:-}" ]]; then
  echo "Missing GHCR credentials" >&2
  exit 1
fi

echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USERNAME}" --password-stdin

docker compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" pull
docker compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" up -d postgres redis minio
docker compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" run --rm api-migrate
docker compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" up -d api worker web
docker compose --project-name "${project_name}" --env-file "${env_file}" -f "${compose_file}" ps

rm -f "${bundle_path}"
docker image prune -f >/dev/null 2>&1 || true
