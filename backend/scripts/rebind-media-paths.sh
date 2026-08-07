#!/usr/bin/env bash
#
# Reconcile DB media file paths + hashes after moving the uploads root.
#
# The image_summary / audio_summaries tables store absolute file_path values
# (and SHA-256 path-derived primary keys / hash columns). When the upload base
# dir changes (e.g. ${HOME}/uploads  ->  ./uploads), those stored paths no
# longer resolve and the recomputed hashes no longer match, so lookup fails.
#
# This script rewrites every affected row to the new base dir and recomputes
# the path-derived hash columns (id, image_hash / file_hash) using PG's own
# sha256() so the DB matches what the application computes at runtime.
#
# Idempotent: rows that no longer start with the old prefix are left alone.
#
# Usage:
#   backend/scripts/rebind-media-paths.sh
#
# Env overrides (all optional):
#   PGHOST    host:     localhost (same as application.yaml)
#   PGPORT    port:     45432
#   PGPASSWORD password: secret
#   PGDATABASE  database: wechat
#   PGUSER      user:     myuser
#   OLD_UPLOAD_DIR  old base dir (default: $HOME/uploads)
#   NEW_UPLOAD_DIR  new base dir (default: $PWD/uploads)
#   FLUSH_REDIS      set to "1" to also flush Redis caches after migrating
set -euo pipefail

export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-45432}"
export PGPASSWORD="${PGPASSWORD:-secret}"
export PGDATABASE="${PGDATABASE:-wechat}"
export PGUSER="${PGUSER:-myuser}"

NEW_ROOT="${NEW_UPLOAD_DIR:-$(pwd)/uploads}"
NEW_PREFIX="${NEW_ROOT%/}/"

# Auto-detect the currently stored uploads root from the DB unless explicitly
# overridden. Keeps the script reusable regardless of the base dir name/location.
if [ -n "${OLD_UPLOAD_DIR:-}" ]; then
  OLD_ROOT="${OLD_UPLOAD_DIR}"
else
  OLD_ROOT="$(psql -t -A -c "SELECT substring(file_path FROM 1 FOR position('/uploads/' IN file_path) + 8)
                            FROM image_summary
                            WHERE file_path LIKE '%/uploads/%'
                            LIMIT 1")"
  if [ -z "${OLD_ROOT}" ]; then
    echo "No '/uploads/' paths found in image_summary and OLD_UPLOAD_DIR not set. Nothing to migrate." >&2
    exit 0
  fi
  echo "Detected stored uploads root from DB: ${OLD_ROOT}"
fi
OLD_PREFIX="${OLD_ROOT%/}/"

echo "Old uploads root: ${OLD_ROOT}"
echo "New uploads root: ${NEW_ROOT}"

echo "Migrating image_summary and audio_summaries in one transaction ..."
psql -v ON_ERROR_STOP=1 <<SQL
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

WITH moved AS (
  SELECT id,
         '${NEW_PREFIX}' || substring(file_path FROM length('${OLD_PREFIX}') + 1) AS new_path
  FROM image_summary
  WHERE file_path LIKE '${OLD_PREFIX}%'
)
UPDATE image_summary p
SET file_path   = m.new_path,
    id          = encode(digest(m.new_path, 'sha256'), 'hex'),
    image_hash  = encode(digest(m.new_path, 'sha256'), 'hex')
FROM moved m
WHERE p.id = m.id;

WITH moved AS (
  SELECT id,
         '${NEW_PREFIX}' || substring(file_path FROM length('${OLD_PREFIX}') + 1) AS new_path
  FROM audio_summaries
  WHERE file_path LIKE '${OLD_PREFIX}%'
)
UPDATE audio_summaries p
SET file_path  = m.new_path,
    id         = encode(digest(m.new_path, 'sha256'), 'hex'),
    file_hash  = encode(digest(m.new_path, 'sha256'), 'hex')
FROM moved m
WHERE p.id = m.id;

COMMIT;
SQL

echo "Verification: remaining rows still on the old prefix (should be 0):"
psql -t -c "SELECT count(*) FROM image_summary WHERE file_path LIKE '${OLD_PREFIX}%'" \
      -c "SELECT count(*) FROM audio_summaries WHERE file_path LIKE '${OLD_PREFIX}%'"

if [ "${FLUSH_REDIS:-0}" = "1" ]; then
  REDIS_PORT="${REDIS_PORT:-46379}"
  echo "Flushing Redis caches on port ${REDIS_PORT} to clear stale summary entries ..."
  redis-cli -p "${REDIS_PORT}" FLUSHDB
fi

echo "Done. Restart the backend so in-flight Redis cache state is refreshed."