#!/usr/bin/env bash
set -euo pipefail

query="${1:-}"
if [[ -z "$query" ]]; then
  echo "Usage: $0 <document-id-or-title-fragment>" >&2
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
  d.id,
  d.title,
  d.source_type,
  d.file_name,
  d.mime_type,
  d.parse_status,
  d.upload_status,
  d.diagnosis_code,
  d.diagnosis_summary,
  d.parse_error_message,
  d.object_key,
  d.upload_id,
  d.imported_at,
  d.updated_at,
  j.id as latest_job_id,
  j.job_type,
  j.status as latest_job_status,
  j.attempt,
  j.diagnosis_code as latest_job_diagnosis_code,
  j.error_message as latest_job_error_message
from documents d
left join lateral (
  select id, job_type, status, attempt, diagnosis_code, error_message
  from document_parse_jobs
  where document_id = d.id
  order by created_at desc
  limit 1
) j on true
where d.id::text = :'query'
   or d.title ilike '%' || :'query' || '%'
order by d.imported_at desc
limit 20;
SQL
)"

docker exec -i "$postgres_container" psql \
  -U "$POSTGRES_USER" \
  -d "$POSTGRES_DB" \
  -v query="$query" \
  -c "$sql"
