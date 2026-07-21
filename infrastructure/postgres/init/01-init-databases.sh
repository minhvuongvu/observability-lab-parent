#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Provisions one database and one owning role per consumer.
#
# The container image creates only POSTGRES_DB. Everything else is created
# here so that the Order Service and Keycloak get separate credentials and
# cannot read each other's data, even though they share one engine.
#
# Runs once, on first initialisation of an empty data directory. Dropping the
# postgres-data volume is what makes it run again.
# ---------------------------------------------------------------------------
set -euo pipefail

provision() {
  local db="$1" user="$2" password="$3"

  echo "  provisioning database '${db}' owned by role '${user}'"

  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<SQL
CREATE ROLE "${user}" WITH LOGIN PASSWORD '${password}';
CREATE DATABASE "${db}" OWNER "${user}" ENCODING 'UTF8';
GRANT ALL PRIVILEGES ON DATABASE "${db}" TO "${user}";
SQL

  # PostgreSQL 15 revoked CREATE on the public schema from PUBLIC. Without
  # this the owner can connect but cannot create a single table, which
  # surfaces much later as a confusing migration failure.
  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${db}" <<SQL
ALTER SCHEMA public OWNER TO "${user}";
GRANT ALL ON SCHEMA public TO "${user}";
SQL
}

echo "Provisioning application databases"
provision "${ORDER_DB_NAME}"    "${ORDER_DB_USER}"    "${ORDER_DB_PASSWORD}"
provision "${KEYCLOAK_DB_NAME}" "${KEYCLOAK_DB_USER}" "${KEYCLOAK_DB_PASSWORD}"
echo "Application databases ready"

# ---------------------------------------------------------------------------
# Read-only monitoring role, for postgres-exporter (step 16).
#
# pg_monitor is a built-in role that grants exactly what a metrics exporter
# needs - the pg_stat_* views, pg_ls_dir and friends - and nothing else. It
# cannot read a single row of application data, which matters because the
# exporter holds this password in an environment variable and publishes what it
# reads over unauthenticated HTTP.
#
# NOSUPERUSER and NOCREATEDB are the defaults and are stated anyway: a
# monitoring account that can be widened later without anybody noticing is how
# "read-only" quietly stops being true.
# ---------------------------------------------------------------------------
if [ -n "${MONITOR_DB_USER:-}" ]; then
  echo "Provisioning monitoring role '${MONITOR_DB_USER}'"

  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<SQL
CREATE ROLE "${MONITOR_DB_USER}" WITH LOGIN PASSWORD '${MONITOR_DB_PASSWORD}'
  NOSUPERUSER NOCREATEDB NOCREATEROLE;
GRANT pg_monitor TO "${MONITOR_DB_USER}";
SQL

  # CONNECT on each application database, so the exporter can report per-database
  # statistics. Still no table privileges anywhere.
  for db in "${POSTGRES_DB}" "${ORDER_DB_NAME}" "${KEYCLOAK_DB_NAME}"; do
    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
      -c "GRANT CONNECT ON DATABASE \"${db}\" TO \"${MONITOR_DB_USER}\";"
  done

  echo "Monitoring role ready"
fi
