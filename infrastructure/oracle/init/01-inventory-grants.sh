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
