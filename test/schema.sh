#!/usr/bin/env bash
# Create the test role and the two databases with identical table schemas.
# Idempotent: safe to re-run; re-creates the databases from scratch.
#
# Assumes a local Postgres where `psql -U postgres` connects via peer auth.
set -euo pipefail

PSQL=(psql -v ON_ERROR_STOP=1 -U postgres)
ROLE=pgtest
PASSWORD=pgtestpw
DB1=pgtest_db1
DB2=pgtest_db2

"${PSQL[@]}" -d postgres <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='$ROLE') THEN
    CREATE ROLE $ROLE LOGIN PASSWORD '$PASSWORD' SUPERUSER;
  END IF;
END\$\$;
SQL

dropdb --if-exists "$DB1" -U postgres
dropdb --if-exists "$DB2" -U postgres
createdb "$DB1" -U postgres
createdb "$DB2" -U postgres

# The identical table definition is applied to both databases.
APPLY_SCHEMA=$(cat <<'SQL'
CREATE TABLE users (
    id    SERIAL PRIMARY KEY,
    name  TEXT NOT NULL,
    email TEXT,
    age   INT
);
CREATE TABLE products (
    sku       TEXT PRIMARY KEY,
    price     NUMERIC(10,2),
    in_stock  BOOLEAN DEFAULT true
);
-- Exercises the major JDBC-backed Postgres types in one table.
CREATE TABLE all_types (
    id   SERIAL PRIMARY KEY,
    b    BOOLEAN,
    si   SMALLINT,
    i    INTEGER,
    bi   BIGINT,
    r    REAL,
    dp   DOUBLE PRECISION,
    n    NUMERIC(12,4),
    t    TEXT,            -- includes quotes / backslash / text-case in db1
    d    VARCHAR(20),
    c    CHAR(5),
    dt   DATE,
    tm   TIME,
    tsz  TIMESTAMP WITH TIME ZONE,
    ts   TIMESTAMP WITHOUT TIME ZONE,
    ba   BYTEA,
    u    UUID,
    jb   JSONB
);
-- Table with a COMPOSITE primary key (2 columns).
CREATE TABLE enrollments (
    student_id INT,
    course_id  VARCHAR(10),
    grade      NUMERIC(3,1),
    term       DATE,
    PRIMARY KEY (student_id, course_id)
);
-- Table that is intentionally empty in db1 (must be ignored by pgdata).
CREATE TABLE empty_table (
    id   SERIAL PRIMARY KEY,
    note TEXT
);
SQL
)

"${PSQL[@]}" -d "$DB1" -c "$APPLY_SCHEMA"
"${PSQL[@]}" -d "$DB2" -c "$APPLY_SCHEMA"

echo "schema ready: databases $DB1 and $DB2 created with identical tables"
echo "next: ./populate.sh"