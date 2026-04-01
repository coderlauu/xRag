#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

: "${DEPLOY_ENVIRONMENT:?DEPLOY_ENVIRONMENT is required}"
: "${DEPLOY_PATH:?DEPLOY_PATH is required}"
: "${DEPLOY_ENV_FILE:?DEPLOY_ENV_FILE is required}"
: "${GHCR_USERNAME:?GHCR_USERNAME is required}"
: "${GHCR_TOKEN:?GHCR_TOKEN is required}"
: "${SSH_HOST:?SSH_HOST is required}"
: "${SSH_PRIVATE_KEY:?SSH_PRIVATE_KEY is required}"
: "${SSH_USER:?SSH_USER is required}"
: "${XRAG_API_IMAGE:?XRAG_API_IMAGE is required}"
: "${XRAG_WORKER_IMAGE:?XRAG_WORKER_IMAGE is required}"
: "${XRAG_WEB_IMAGE:?XRAG_WEB_IMAGE is required}"
: "${XRAG_IMAGE_TAG:?XRAG_IMAGE_TAG is required}"

ssh_port="${SSH_PORT:-22}"
bundle_path="/tmp/xrag-${DEPLOY_ENVIRONMENT}-${XRAG_IMAGE_TAG}.tar.gz"
bundle_file="$(mktemp /tmp/xrag-deploy-XXXXXX.tar.gz)"
key_file="$(mktemp /tmp/xrag-ssh-key-XXXXXX)"

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

scp "${scp_base_args[@]}" "${bundle_file}" "${SSH_USER}@${SSH_HOST}:${bundle_path}"

env_file_b64="$(printf '%s' "${DEPLOY_ENV_FILE}" | base64 | tr -d '\n')"

printf -v env_file_b64_q '%q' "${env_file_b64}"
printf -v ghcr_user_q '%q' "${GHCR_USERNAME}"
printf -v ghcr_token_q '%q' "${GHCR_TOKEN}"
printf -v api_image_q '%q' "${XRAG_API_IMAGE}"
printf -v worker_image_q '%q' "${XRAG_WORKER_IMAGE}"
printf -v web_image_q '%q' "${XRAG_WEB_IMAGE}"
printf -v deploy_path_q '%q' "${DEPLOY_PATH}"
printf -v deploy_env_q '%q' "${DEPLOY_ENVIRONMENT}"
printf -v image_tag_q '%q' "${XRAG_IMAGE_TAG}"
printf -v bundle_path_q '%q' "${bundle_path}"

ssh "${ssh_base_args[@]}" "${SSH_USER}@${SSH_HOST}" \
  "XRAG_ENV_FILE_B64=${env_file_b64_q} GHCR_USERNAME=${ghcr_user_q} GHCR_TOKEN=${ghcr_token_q} XRAG_API_IMAGE=${api_image_q} XRAG_WORKER_IMAGE=${worker_image_q} XRAG_WEB_IMAGE=${web_image_q} bash -s -- ${deploy_path_q} ${deploy_env_q} ${image_tag_q} ${bundle_path_q}" \
  < "${repo_root}/deploy/scripts/remote-deploy.sh"
