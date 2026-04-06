#!/usr/bin/env bash
set -euo pipefail

query="${1:-}"
if [[ -z "$query" ]]; then
  echo "Usage: $0 <upload-id-or-file-name-fragment>" >&2
  exit 1
fi

env_file="${XRAG_ENV_FILE:-/srv/xrag/shared/production.env}"
postgres_container="${XRAG_POSTGRES_CONTAINER:-xrag-production-postgres-1}"

if [[ ! -f "$env_file" ]]; then
  echo "Env file not found: $env_file" >&2
  echo "Set XRAG_ENV_FILE to the deploy env file on the target host." >&2
  exit 1
fi

set -a
source "$env_file"
set +a

sql="$(cat <<'SQL'
select
  u.id,
  u.file_name,
  u.mime_type,
  u.file_size,
  u.upload_mode,
  u.status,
  u.provider_upload_id,
  u.part_count,
  u.uploaded_part_count,
  u.error_code,
  u.error_message,
  u.object_key,
  u.created_at,
  u.completed_at,
  d.id as document_id,
  d.parse_status,
  d.upload_status,
  d.diagnosis_code,
  j.id as latest_job_id,
  j.status as latest_job_status,
  j.diagnosis_code as latest_job_diagnosis_code
from uploads u
left join documents d on d.upload_id = u.id
left join lateral (
  select id, status, diagnosis_code
  from document_parse_jobs
  where document_id = d.id
  order by created_at desc
  limit 1
) j on true
where u.id::text = :'query'
   or u.file_name ilike '%' || :'query' || '%'
order by u.created_at desc
limit 20;
SQL
)"

docker exec -i "$postgres_container" psql \
  -U "$POSTGRES_USER" \
  -d "$POSTGRES_DB" \
  -v query="$query" \
  -c "$sql"
