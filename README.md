# pgdata

Reconciling reference data between PostgreSQL databases.

`pgschema` keeps a database's *schema* in line with a declarative SQL
definition. `pgdata` is its companion for *reference data*: the small, fairly
static tables (lookups, enumerations, config) that a schema alone cannot
manage. Given a canonical dump of the data that *should* exist, it produces a
change script that brings a target database into line with it — and can apply
that script for you.

`pgdata` is a single self-contained Groovy script (`pgdata.groovy`) that drives
two reconciliation styles and everything needed around them:

- **`plan`** — compare a *script of INSERTs* against a live database, and print
  the change script to bring the database in line with the script.
- **`dbplan`** — compare two *live databases* directly and print the change
  script to reconcile the second to the first.

## Why reference keys matter (read this first)

`pgdata` identifies a row solely by its **primary key**. For reference data this
is a feature and a constraint:

- **Always include explicit primary-key values** in your INSERTs. Keys are what
  link a declarative row to the row already in the database. If you omit a key,
  the tool cannot know which database row it corresponds to.
- **Avoid `serial`/`bigserial` (or any `DEFAULT nextval(...)`) on the key
  column** of your reference tables. Auto-generated keys are not determinable
  from the declarative data, so they could change between runs or between
  databases, and a plan keyed on them would be unstable. If a table relies on a
  generated key, it is a poor fit for declarative reference data — prefer an
  explicit, stable business key instead.

A `dump` will always emit the primary key explicitly, so data round-tripped
through `dump` → `plan` is safe.

## Prerequisites

- **groovy** with Grapes support (the JDBC driver is fetched automatically).
- A **PostgreSQL** server, with the standard client tools on your `PATH`:
  `psql`, `createdb`, `dropdb`, and `pg_dump`. `plan`/`apply` use `pg_dump
  --schema-only` to reproduce the full schema (constraints included) in a
  temporary database.
- **Credentials** — a role that can connect to, and (for `plan`/`apply`)
  create databases on, the target server.

Passwords are never on the command line; they come from the environment:

| Variable | Used by |
|---|---|
| `PGPASSWORD` | single-database modes (`list`, `dump`, `plan`, `apply`) |
| `PGPASSWORD1` / `PGPASSWORD2` | `dbplan` (the two live databases) |

## The workflow

The same loop as pgschema — **capture → plan → apply** — but for data.

### 1 — Capture reference data (`dump`)

Dump the rows of just the tables you care about. A table list after `--` limits
the dump to those tables, which matters when the source is a full development or
production database that contains far more than reference data:

```bash
PGPASSWORD=secret ./pgdata.groovy dump \
    --host localhost --db devdb --user app -- country currency
```

This connects only to `devdb` and prints an `INSERT` per row of the listed
tables. Empty tables are skipped. Save it — it becomes your canonical data
definition, just as `schema.sql` is pgschema's:

```bash
./pgdata.groovy dump --host localhost --db devdb --user app \
    -- country currency > reference.sql
```

`reference.sql` (`INSERT` statements, one per row, with explicit keys):

```sql
INSERT INTO "public"."country" (iso, name) VALUES ('AU', 'Australia');
INSERT INTO "public"."country" (iso, name) VALUES ('NZ', 'New Zealand');
```

### 2. Edit the reference data

Edit `reference.sql` to describe the data you *want* the target to have — add a
row, change a value, delete a line. Same idea as editing `schema.sql`.

### 3. Generate a plan (`plan`)

`plan` reconciles your reference *script* against the target *database*. It
takes a schema-only copy of the target, loads your INSERTs into that, then
diffs the two and writes the statements needed to bring the target in line to
a file of your choice (mandatory `--output`). Crucially, the temp schema is
produced by `pg_dump --schema-only`, so it carries the **full constraint set**
(NOT NULL, UNIQUE, defaults, primary keys) that the real database has.

```bash
./pgdata.groovy plan --host localhost --db testdb --user app \
    --file reference.sql --output plan.sql
```

`plan` then runs a **verification (test) phase** so you can ship with confidence
that the plan actually works:

1. It takes a *data* dump of just the tables being reconciled (`pg_dump`, not
   schema-only, scoped to the same `-- T...` table list as the reference) and
   spins it up in a second temporary database — a replica of the relevant
   tables, with the real data.
2. It applies the plan just written to `--output` against that replica.
3. It compares the replica against the reference data. If they match, the plan
   is confirmed to work against real data; if any reconciling table were
   missed, the comparison would catch it.

```
Plan written to: plan.sql (500 bytes)
TEST PASSED: plan applied to a copy of 'testdb' and matches the reference data.
```

If the replica does **not** match after applying the plan, `plan` fails with a
per-record message describing each difference (table, key, column, applied vs
expected). If the plan script itself fails to load, `plan` reports the failure
and leaves the full `psql` log in a temp file whose path it prints, so you can
read what went wrong.

Rows already identical produce nothing. This step touches neither the target
nor production — the staging and verification databases are temporary and
removed afterwards.

### 4. Apply the changes (`apply`)

- **Two-phase (safe):** generate the plan to a file, inspect it, apply it:

  ```bash
  ./pgdata.groovy plan --host localhost --db testdb --user app \
      --file reference.sql --output plan.sql
  ./pgdata.groovy apply --host localhost --db testdb --user app --plan plan.sql
  ```

- **Direct apply (development):** `apply --file` runs plan+apply in one step:

  ```bash
  ./pgdata.groovy apply --host localhost --db testdb --user app --file reference.sql
  ```

Both create a temporary database for the staging step, drop it afterwards, and
apply the reconciliation directly to the numbered target.

### Comparing two live databases (`dbplan`)

When the source is another database rather than a script, `dbplan` compares two
databases directly — no temp schema needed:

```bash
PGPASSWORD1=a PGPASSWORD2=b ./pgdata.groovy dbplan \
    --host1 prod.host --db1 refdb --user1 app \
    --host2 localhost --db2 testdb --user2 app
```

`dbplan` reads the source database `db1`, keys rows on their primary key, and
emits the `INSERT`/`UPDATE`/`DELETE` statements to reconcile `db2` to `db1`.

### Running only the verification phase (`test`)

`plan` always runs the test phase at the end, but you can run just that phase
on its own. `test` takes the same database arguments as `plan`, plus a
`--file` (reference data, to build the reference database) and a `--plan`
(the change script to verify). It reuses the exact same staging-build and
verification steps as `plan`, skipping only the plan generation in the middle:

```bash
./pgdata.groovy test --host localhost --db testdb --user app \
    --file reference.sql --plan plan.sql
```

This is useful for re-running the test with a plan you already generated or
hand-edited — the same failure modes as `plan` (load failure, or the applied
replica not matching the reference data) surface here too.

### Checking your work

Because every mode only emits differences, applying the plan and re-planning is
a self-check: after a successful apply, re-planning produces **zero** data
statements.

```bash
./pgdata.groovy plan --host localhost --db testdb --user app \
    --file reference.sql --output plan.sql
# -> TEST PASSED (the plan now produces no differences), and no error messages.
```

## Command reference

| Command | What it does |
|---|---|
| `pgdata.groovy dump --host H --db D --user U [--schema S] [-- T...]` | Print `INSERT`s for the listed tables (or all non-empty). |
| `pgdata.groovy list --host H --db D --user U [--schema S]` | Print names of tables that have data, one per line. |
| `pgdata.groovy plan --host H --db D --user U [--schema S] --file f.sql --output plan.sql` | Diff `f.sql` (INSERTs) against `D`, write the change script to `plan.sql`, then run the verification phase. |
| `pgdata.groovy test --host H --db D --user U [--schema S] --file f.sql --plan plan.sql` | Run only the verification phase for an existing `plan.sql`. |
| `pgdata.groovy dbplan --host1 .. --db1 .. --user1 .. --host2 .. --db2 .. --user2 ..` | Diff two live databases (`db2` → `db1`), print the change script. |
| `pgdata.groovy apply --host H --db D --user U [--schema S] --plan plan.sql` | Run `plan.sql` against the target via `psql`. |
| `pgdata.groovy apply --host H --db D --user U [--schema S] --file f.sql` | `plan` + `apply` in one step. |

Common flags: `--host`, `--db`, `--user` identify the *target* database;
`--schema` defaults to `public`; a `-- T...` list after the options scopes
`dump` / the temp-schema step; `--file` is a reference-INSERT script; `--plan`
is a ready-made change script.

Passwords: single-db modes use `PGPASSWORD`; `dbplan` uses `PGPASSWORD1` and
`PGPASSWORD2` (both required).

## Supported types

Literals are written per the Postgres/JDBC value class, so the common types
round-trip losslessly:

`BOOLEAN`, `SMALLINT`, `INTEGER`, `BIGINT`, `REAL`, `DOUBLE PRECISION`,
`NUMERIC`, `TEXT`, `VARCHAR`, `CHAR`, `DATE`, `TIME`, `TIMESTAMP`,
`TIMESTAMPTZ` (emitted with an explicit offset), `BYTEA` (`decode(…,'hex')`),
`UUID`, `JSONB`, and `NULL`. Strings are single-quoted with embedded single
quotes doubled; double quotes and backslashes are handled per Postgres's
default `standard_conforming_strings=on`.

Primary keys are discovered from the catalog and keyed on — including
composite keys, where the `WHERE` clause spans every key column.

## How it's built

`pgdata.groovy` is organised as a small, well-factored Groovy API with the
command-line modes as thin shells over it:

- rule-based value formatting (`lit`), the reconcile loop (`reconcile()`,
  pure whose inputs are a list of source rows, target rows, and columns) and
  the database access are separate, so the same reconciliation engine backs
  both `plan` (script → temp db → real database) and `dbplan` (database →
  database).
- `plan` / `apply` reconstruct the temp schema with `pg_dump --schema-only`
  for faithful constraints, and load/unload it with `psql` and `dropdb` (all
  via `ProcessBuilder`, with `PGPASSWORD` exported to the tools).

## Development / testing

`test/` contains a self-contained harness. `schema.sh` creates two divergent
databases, `populate_db1.sh` / `populate_db2.sh` load fixture data into each,
and `check.sh` runs the engine end-to-end, asserts the expected statements,
applies them, verifies the two databases converge, and re-runs to confirm no
statements remain:

```bash
./test/check.sh
```

The fixtures exercise the type coverage above, single and composite primary
keys, empty-source tables (which must be ignored), quote/backslash
escaping, and the full `INSERT` / `UPDATE` / `DELETE` matrix.