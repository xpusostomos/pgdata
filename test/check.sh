#!/usr/bin/env bash
# Check harness for pgdata.groovy — engine, plan, test, and failure cases.
#
#   A. dbplan (db-to-db): generate, assert, apply, converge-to-zero.
#   B. plan  (script-to-db): plan --output writes a plan + verification passes.
#   C. test  (standalone verification): success path.
#   D1. test with a plan that fails to load      -> exit 1, log preserved.
#   D2. test with a plan that doesn't converge   -> exit 1, per-record diff.
#   E. no leftover temp DBs on any path.
#
# Usage: ./check.sh [path/to/pgdata.groovy]
set -euo pipefail

PGDATA="${1:-$(cd "$(dirname "$0")/.." && pwd)/pgdata.groovy}"
cd "$(cd "$(dirname "$0")/.." && pwd)"

DB1=pgtest_db1
DB2=pgtest_db2
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "ERROR: $1" >&2; }
# if any prior assertion failed, bail (set -e would already catch many)
checkpoint() { if [[ $FAIL -ne 0 ]]; then echo "Aborting early due to prior failures."; exit 1; fi; }

reset_dbs() {
    # Clear any stale temp databases from previous (possibly crashed) runs so the
    # temp-DB-leak assertions only ever see leaks from THIS run.
    psql -X -U postgres -tAc "select datname from pg_database where datname like 'tmp_pgdata_%'" 2>/dev/null \
        | while read -r d; do [[ -n "$d" ]] && dropdb --if-exists --force -U postgres "$d" >/dev/null 2>&1; done
    ./test/schema.sh >/dev/null; ./test/populate_db1.sh >/dev/null; ./test/populate_db2.sh >/dev/null
}
# -X skips ~/.psqlrc (which may print notices like "Pager usage is off." on
# stdout); tr keeps only digits so stray output can never fake a non-zero count.
np_dbs() { psql -X -U postgres -tAc "select count(*) from pg_database where datname like 'tmp_pgdata_%'" 2>/dev/null | tr -dc '0-9'; }

reset_dbs

# =============================================================================
echo "== A. dbplan (db-to-db): generate, assert, apply, converge =="
run_dbplan() { PGPASSWORD1=pgtestpw PGPASSWORD2=pgtestpw groovy "$PGDATA" dbplan \
    --host1 localhost --host2 localhost --db1 "$DB1" --db2 "$DB2" \
    --user1 pgtest --user2 pgtest; }
run_dbplan > "$WORK/a1.sql" 2>/dev/null
grep -vE '^-- |^$' "$WORK/a1.sql" > "$WORK/a1data.txt" || true

echo "  assertions:"
grep -q '^INSERT INTO "public"."users"'   "$WORK/a1data.txt" && ok "users INSERT" || bad "missing users INSERT"
grep -q '^INSERT INTO "public"."all_types"' "$WORK/a1data.txt" && ok "all_types INSERT" || bad "missing all_types INSERT"
grep -qF "O''Brien" "$WORK/a1data.txt" && ok "single-quote escaping" || bad "quote escaping"
grep -qF 'DELETE FROM "public"."users" WHERE id = 5;' "$WORK/a1data.txt" && ok "users DELETE (ghost)" || bad "missing users DELETE"
grep -q '^INSERT INTO "public"."enrollments"' "$WORK/a1data.txt" && ok "enrollments INSERT" || bad "missing enrollments INSERT"
grep -q "empty_table" "$WORK/a1data.txt" && bad "empty_table must be ignored" || ok "empty_table ignored"
# composite pk WHERE spans both cols
if ! grep -E '"public"."enrollments"' "$WORK/a1data.txt" | grep "WHERE" \
      | grep -qvE "WHERE student_id = [0-9]+ AND course_id = '[^']*'"; then
  ok "composite-pk WHERE spans both columns"
else
  bad "enrollments WHERE must span both pk cols"
fi
checkpoint

# apply generated statements to db2, then re-run -> converged
psql -v ON_ERROR_STOP=1 -U postgres -d "$DB2" -f "$WORK/a1.sql" >/dev/null
run_dbplan > "$WORK/a2.sql" 2>/dev/null
n=$(grep -cvE '^-- |^$' "$WORK/a2.sql" || true)
(( n == 0 )) && ok "dbplan converges to zero after apply" || bad "dbplan not converged ($n statements)"
checkpoint

# =============================================================================
echo "B. plan (script -> db): dump reference, plan --output, verify phase"
reset_dbs
PGPASSWORD=pgtestpw groovy "$PGDATA" dump --host localhost --db "$DB1" --user pgtest -- users products \
    > "$WORK/ref.sql" 2>/dev/null
PGPASSWORD=pgtestpw groovy "$PGDATA" plan --host localhost --db "$DB2" --user pgtest \
    --file "$WORK/ref.sql" --output "$WORK/planB.sql" > "$WORK/planB.out" 2>/dev/null

echo "  assertions:"
grep -q "TEST PASSED" "$WORK/planB.out" && ok "plan verification phase (TEST PASSED)" || bad "plan verify did not pass"
[[ -s "$WORK/planB.sql" ]] && ok "plan written to --output" || bad "--output not written"
grep -q 'UPDATE "public"."products" SET price = 19.50' "$WORK/planB.sql" && ok "plan has products UPDATE" || bad "plan missing products UPDATE"
[[ "$(np_dbs)" == "0" ]] && ok "no leftover temp DBs after plan" || bad "plan leaked temp DBs"
checkpoint

# =============================================================================
echo "C. test (standalone): good plan passes"
PGPASSWORD=pgtestpw groovy "$PGDATA" test --host localhost --db "$DB2" --user pgtest \
    --file "$WORK/ref.sql" --plan "$WORK/planB.sql" > "$WORK/testC.out" 2>&1
grep -q "TEST PASSED" "$WORK/testC.out" && ok "test mode passes on good plan" || bad "test mode failed on good plan"
[[ "$(np_dbs)" == "0" ]] && ok "no temp DBs after test success" || bad "test leaked temp DBs"
checkpoint

# =============================================================================
echo "C2. --use-copy: COPY format dump, round-trip load, COPY in plan"
reset_dbs
PGPASSWORD=pgtestpw groovy "$PGDATA" dump --host localhost --db "$DB1" --user pgtest --use-copy all_types \
    > "$WORK/copydump.sql" 2>/dev/null

echo "  assertions:"
grep -qP '^COPY "public"\."all_types" \(id,\tb,\tsi,' "$WORK/copydump.sql" \
    && ok "COPY header with tab-separated column names" || bad "COPY header missing tabs"
grep -qP '\tt\t|^t\t' "$WORK/copydump.sql" && ok "boolean as t/f" || bad "boolean not t/f"
grep -qF '\\x0001ff' "$WORK/copydump.sql" && ok "bytea as escaped hex" || bad "bytea wrong"
grep -qF $'\t\\N\t' "$WORK/copydump.sql" && ok "null as \N" || bad "null not \\N"
grep -qF "a\\\\b" "$WORK/copydump.sql" && ok "backslash escaped in text" || bad "backslash not escaped"
grep -qx '\\.' "$WORK/copydump.sql" && ok "\. terminator present" || bad "missing \\."

# COPY-format dump feeds plan as the reference (CopyManager load path)
PGPASSWORD=pgtestpw groovy "$PGDATA" plan --host localhost --db "$DB2" --user pgtest \
    --file "$WORK/copydump.sql" --output "$WORK/planC2.sql" > "$WORK/c2.out" 2>/dev/null
grep -q "TEST PASSED" "$WORK/c2.out" && ok "COPY-format reference loads and verifies" || bad "COPY reference round-trip failed"

# plan --use-copy emits COPY for INSERT rows; apply --plan loads it via execSql
reset_dbs
PGPASSWORD=pgtestpw groovy "$PGDATA" dump --host localhost --db "$DB1" --user pgtest users products \
    > "$WORK/refC2.sql" 2>/dev/null
PGPASSWORD=pgtestpw groovy "$PGDATA" plan --host localhost --db "$DB2" --user pgtest \
    --file "$WORK/refC2.sql" --output "$WORK/planUC.sql" --use-copy >/dev/null 2>&1
grep -qE '^COPY ' "$WORK/planUC.sql" && ok "plan --use-copy emits COPY block" || bad "plan lacks COPY block"
PGPASSWORD=pgtestpw groovy "$PGDATA" apply --host localhost --db "$DB2" --user pgtest \
    --plan "$WORK/planUC.sql" >/dev/null 2>&1
ndiff=$(PGPASSWORD1=pgtestpw PGPASSWORD2=pgtestpw groovy "$PGDATA" dbplan \
    --host1 localhost --host2 localhost --db1 "$DB1" --db2 "$DB2" \
    --user1 pgtest --user2 pgtest -- users products 2>/dev/null | grep -cvE '^-- |^$' || true)
[[ "$ndiff" == "0" ]] && ok "COPY plan applies and converges (scoped tables)" || bad "COPY plan left $ndiff diffs"
[[ "$(np_dbs)" == "0" ]] && ok "no temp DBs after use-copy section" || bad "use-copy leaked temp DBs"
checkpoint

# =============================================================================
echo "D1. failure: plan script fails to load -> exit 1 + log preserved"
reset_dbs
printf 'INSERT INTO no_such_table (id) VALUES (1);\n' > "$WORK/badplan.sql"
set +e
PGPASSWORD=pgtestpw groovy "$PGDATA" test --host localhost --db "$DB2" --user pgtest \
    --file "$WORK/ref.sql" --plan "$WORK/badplan.sql" >/dev/null 2> "$WORK/d1.err"
rc_d1=$?
set -e
echo "  assertions:"
(( rc_d1 == 1 )) && ok "exit code 1" || bad "expect exit 1, got $rc_d1"
grep -q "the plan script failed to apply" "$WORK/d1.err" && ok "load-failure message" || bad "missing load-failure msg"
logpath=$(grep -o '/tmp/pgdata_apply[^.]*\.log' "$WORK/d1.err" | head -1)
[[ -n "$logpath" && -f "$logpath" ]] && ok "failure log preserved ($logpath)" || bad "log not preserved"
[[ "$(np_dbs)" == "0" ]] && ok "no temp DB leak after load failure" || bad "load-failure leaked temp DBs"
checkpoint

# =============================================================================
echo "D2. failure: plan does NOT converge -> exit 1 + per-record diff"
reset_dbs
printf -- '-- Changes for table users\nUPDATE "public"."users" SET age = 0 WHERE id = 5;\n' > "$WORK/wrongplan.sql"
set +e
PGPASSWORD=pgtestpw groovy "$PGDATA" test --host localhost --db "$DB2" --user pgtest \
    --file "$WORK/ref.sql" --plan "$WORK/wrongplan.sql" >/dev/null 2> "$WORK/d2.err"
rc_d2=$?
set -e
echo "  assertions:"
(( rc_d2 == 1 )) && ok "exit code 1" || bad "expect exit 1, got $rc_d2"
grep -q "did not produce the reference data" "$WORK/d2.err" && ok "diff-mismatch message" || bad "missing diff-mismatch msg"
grep -Eq "column [a-z_]+ differs on key" "$WORK/d2.err" && ok "per-record column diff present" || bad "missing per-record diff"
[[ "$(np_dbs)" == "0" ]] && ok "no temp DB leak after diff failure" || bad "diff-failure leaked temp DBs"

# =============================================================================
echo ""
echo "RESULT: $PASS passed, $FAIL failed"
if [[ $FAIL -eq 0 ]]; then echo "ALL TESTS PASSED"; rm -f /tmp/pgdata_apply*.log; else echo "SOME TESTS FAILED"; exit 1; fi