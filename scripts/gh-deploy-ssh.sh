#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

: "${DEPLOY_ENVIRONMENT:?DEPLOY_ENVIRONMENT is required}"
: "${DEPLOY_PATH:?DEPLOY_PATH is required}"
: "${DEPLOY_ENV_FILE:?DEPLOY_ENV_FILE is required}"
: "${REGISTRY_HOST:?REGISTRY_HOST is required}"
: "${REGISTRY_USERNAME:?REGISTRY_USERNAME is required}"
: "${REGISTRY_PASSWORD:?REGISTRY_PASSWORD is required}"
: "${SSH_HOST:?SSH_HOST is required}"
: "${SSH_PRIVATE_KEY:?SSH_PRIVATE_KEY is required}"
: "${SSH_USER:?SSH_USER is required}"
: "${XRAG_API_IMAGE:?XRAG_API_IMAGE is required}"
: "${XRAG_WORKER_IMAGE:?XRAG_WORKER_IMAGE is required}"
: "${XRAG_WEB_IMAGE:?XRAG_WEB_IMAGE is required}"
: "${XRAG_IMAGE_TAG:?XRAG_IMAGE_TAG is required}"

ssh_port="${SSH_PORT:-22}"
remote_tmp_dir="${DEPLOY_PATH}/shared/tmp"
bundle_path="${remote_tmp_dir}/xrag-${DEPLOY_ENVIRONMENT}-${XRAG_IMAGE_TAG}.tar.gz"
bundle_file="$(mktemp /tmp/xrag-deploy-XXXXXX.tar.gz)"
key_file="$(mktemp /tmp/xrag-ssh-key-XXXXXX)"
deployed_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
previous_api_image=""
previous_worker_image=""
previous_web_image=""

cleanup() {
  rm -f "${bundle_file}" "${key_file}"
}

trap cleanup EXIT

printf '%s\n' "${SSH_PRIVATE_KEY}" > "${key_file}"
tr -d '\r' < "${key_file}" > "${key_file}.normalized"
mv "${key_file}.normalized" "${key_file}"
chmod 600 "${key_file}"
tar -czf "${bundle_file}" deploy

if ! ssh-keygen -y -f "${key_file}" >/dev/null 2>&1; then
  echo "SSH_PRIVATE_KEY is not a valid private key" >&2
  exit 1
fi

ssh_base_args=(
  -i "${key_file}"
  -p "${ssh_port}"
  -o BatchMode=yes
  -o ConnectTimeout=10
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=3
  -o StrictHostKeyChecking=no
)

scp_base_args=(
  -i "${key_file}"
  -P "${ssh_port}"
  -o BatchMode=yes
  -o ConnectTimeout=10
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=3
  -o StrictHostKeyChecking=no
)

if ! ssh "${ssh_base_args[@]}" "${SSH_USER}@${SSH_HOST}" "echo xrag-ssh-ok" >/dev/null 2>&1; then
  cat >&2 <<EOF
Remote SSH authentication failed.
Check these values and server state:
- SSH_HOST
- SSH_PORT
- SSH_USER
- SSH_PRIVATE_KEY
- the matching public key in ~${SSH_USER}/.ssh/authorized_keys
- whether the ${SSH_USER} user is allowed to log in via public key
EOF
  exit 1
fi

project_name="xrag-${DEPLOY_ENVIRONMENT}"
printf -v project_name_q '%q' "${project_name}"
previous_images="$(
  ssh "${ssh_base_args[@]}" "${SSH_USER}@${SSH_HOST}" "bash -s -- ${project_name_q}" 2>/dev/null <<'EOF' || true
project_name="${1:?project name is required}"
docker_bin="docker"

if ! docker info >/dev/null 2>&1; then
  docker_bin="sudo -n docker"
fi

for service in api worker web; do
  container_id="$($docker_bin ps \
    --filter "label=com.docker.compose.project=${project_name}" \
    --filter "label=com.docker.compose.service=${service}" \
    --format '{{.ID}}' | head -n 1)"

  if [[ -n "${container_id}" ]]; then
    image="$($docker_bin inspect "${container_id}" --format '{{.Config.Image}}')"
    printf '%s=%s\n' "${service}" "${image}"
  fi
done
EOF
)"

while IFS='=' read -r service image; do
  case "${service}" in
    api)
      previous_api_image="${image}"
      ;;
    worker)
      previous_worker_image="${image}"
      ;;
    web)
      previous_web_image="${image}"
      ;;
  esac
done <<<"${previous_images}"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "deployed_at=${deployed_at}"
    echo "previous_api_image=${previous_api_image}"
    echo "previous_worker_image=${previous_worker_image}"
    echo "previous_web_image=${previous_web_image}"
  } >>"${GITHUB_OUTPUT}"
fi

printf -v deploy_path_q '%q' "${DEPLOY_PATH}"
ssh "${ssh_base_args[@]}" "${SSH_USER}@${SSH_HOST}" \
  "XRAG_KEEP_RELEASES='${XRAG_KEEP_RELEASES:-5}' XRAG_DISK_WARN_PERCENT='${XRAG_DISK_WARN_PERCENT:-70}' XRAG_DISK_PRUNE_PERCENT='${XRAG_DISK_PRUNE_PERCENT:-80}' XRAG_DISK_FAIL_PERCENT='${XRAG_DISK_FAIL_PERCENT:-95}' XRAG_DOCKER_LOG_TRUNCATE_MB='${XRAG_DOCKER_LOG_TRUNCATE_MB:-200}' XRAG_PROTECTED_RELEASES='${XRAG_IMAGE_TAG}' bash -s -- ${deploy_path_q}" \
  < "${repo_root}/deploy/scripts/disk-guard.sh"

ssh "${ssh_base_args[@]}" "${SSH_USER}@${SSH_HOST}" "mkdir -p '${remote_tmp_dir}' && rm -f '${bundle_path}'"

scp "${scp_base_args[@]}" "${bundle_file}" "${SSH_USER}@${SSH_HOST}:${bundle_path}"

env_file_b64="$(printf '%s' "${DEPLOY_ENV_FILE}" | base64 | tr -d '\n')"

printf -v env_file_b64_q '%q' "${env_file_b64}"
printf -v registry_host_q '%q' "${REGISTRY_HOST}"
printf -v registry_user_q '%q' "${REGISTRY_USERNAME}"
printf -v registry_password_q '%q' "${REGISTRY_PASSWORD}"
printf -v api_image_q '%q' "${XRAG_API_IMAGE}"
printf -v worker_image_q '%q' "${XRAG_WORKER_IMAGE}"
printf -v web_image_q '%q' "${XRAG_WEB_IMAGE}"
printf -v deploy_env_q '%q' "${DEPLOY_ENVIRONMENT}"
printf -v image_tag_q '%q' "${XRAG_IMAGE_TAG}"
printf -v bundle_path_q '%q' "${bundle_path}"

ssh "${ssh_base_args[@]}" "${SSH_USER}@${SSH_HOST}" \
  "XRAG_ENV_FILE_B64=${env_file_b64_q} REGISTRY_HOST=${registry_host_q} REGISTRY_USERNAME=${registry_user_q} REGISTRY_PASSWORD=${registry_password_q} XRAG_API_IMAGE=${api_image_q} XRAG_WORKER_IMAGE=${worker_image_q} XRAG_WEB_IMAGE=${web_image_q} bash -s -- ${deploy_path_q} ${deploy_env_q} ${image_tag_q} ${bundle_path_q}" \
  < "${repo_root}/deploy/scripts/remote-deploy.sh"
