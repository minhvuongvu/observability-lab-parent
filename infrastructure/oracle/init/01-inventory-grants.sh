#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Inventory Service schema privileges.
#
# The image entrypoint already created ${APP_USER} inside the pluggable
# database from APP_USER / APP_USER_PASSWORD. What it does not do is grant a
# tablespace quota. Since 11g the RESOURCE role no longer implies UNLIMITED
# TABLESPACE, so without the grant below the very first INSERT fails with
# ORA-01950 "no privileges on tablespace" - an error that reads like an
# application bug and is anything but.
#
# Runs once, on first initialisation of an empty data directory.
# ---------------------------------------------------------------------------
set -euo pipefail

PDB="${ORACLE_PDB:-FREEPDB1}"
APP="${APP_USER:?APP_USER must be set for the inventory schema}"

echo "Granting inventory schema privileges to ${APP} in ${PDB}"

sqlplus -s -L / as sysdba <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET FEEDBACK OFF

-- Init scripts connect to the container root; the app user lives in the PDB.
ALTER SESSION SET CONTAINER = ${PDB};

ALTER USER ${APP} QUOTA UNLIMITED ON USERS;

GRANT CREATE SESSION,
      CREATE TABLE,
      CREATE SEQUENCE,
      CREATE VIEW,
      CREATE PROCEDURE,
      CREATE TRIGGER,
      CREATE TYPE
   TO ${APP};

EXIT
SQL

echo "Inventory schema privileges applied"

# ---------------------------------------------------------------------------
# Read-only monitoring user, for oracledb_exporter (step 16).
#
# SELECT_CATALOG_ROLE is the narrow answer: it grants SELECT on the data
# dictionary and the V$ performance views - which is the entirety of what a
# metrics exporter reads - and grants nothing at all on application schemas.
# The exporter therefore cannot see a single stock level, which matters because
# it holds this password in an environment variable and publishes what it reads
# over unauthenticated HTTP.
#
# Created in the PDB rather than as a common user: the exporter connects to the
# service, and a C## common user would be a privilege that spans containers for
# no reason.
# ---------------------------------------------------------------------------
if [ -n "${ORACLE_MONITOR_USER:-}" ]; then
  echo "Provisioning monitoring user ${ORACLE_MONITOR_USER} in ${PDB}"

  sqlplus -s -L / as sysdba <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
SET FEEDBACK OFF

ALTER SESSION SET CONTAINER = ${PDB};

CREATE USER ${ORACLE_MONITOR_USER} IDENTIFIED BY "${ORACLE_MONITOR_PASSWORD}";

GRANT CREATE SESSION TO ${ORACLE_MONITOR_USER};
GRANT SELECT_CATALOG_ROLE TO ${ORACLE_MONITOR_USER};

-- Named explicitly as well as through the role. SELECT_CATALOG_ROLE is not
-- enabled by default in every session context, and an exporter that silently
-- returns zero rows is worse than one that fails to connect.
GRANT SELECT ON V_\$SESSION TO ${ORACLE_MONITOR_USER};
GRANT SELECT ON V_\$SYSSTAT TO ${ORACLE_MONITOR_USER};
GRANT SELECT ON V_\$SYSTEM_EVENT TO ${ORACLE_MONITOR_USER};
GRANT SELECT ON V_\$RESOURCE_LIMIT TO ${ORACLE_MONITOR_USER};
GRANT SELECT ON V_\$PROCESS TO ${ORACLE_MONITOR_USER};
GRANT SELECT ON V_\$SQLAREA TO ${ORACLE_MONITOR_USER};
GRANT SELECT ON DBA_TABLESPACE_USAGE_METRICS TO ${ORACLE_MONITOR_USER};
GRANT SELECT ON DBA_TABLESPACES TO ${ORACLE_MONITOR_USER};

EXIT
SQL

  echo "Monitoring user ready"
fi
