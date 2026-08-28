#!/usr/bin/env groovy
@Grab('org.postgresql:postgresql:42.7.4')
@GrabConfig(systemClassLoader = true)

import groovy.sql.Sql
import java.util.Arrays
import java.sql.DriverManager

def CLI = """usage: pgdata.groovy <mode> <options>

modes:
  dump   --host H --db D --user U [--schema S] [-- TABLE ...]
  list   --host H --db D --user U [--schema S]
  plan   --host H --db D --user U [--schema S] --file input.sql --output plan.sql [-- TABLE ...]
  dbplan --host1 H --db1 D --user1 U [--schema1 S] --host2 H --db2 D --user2 U [--schema2 S] [-- TABLE ...]
  apply  --host H --db D --user U [--schema S] --plan plan.sql
  apply  --host H --db D --user U [--schema S] --file input.sql [-- TABLE ...]

Passwords come from the environment:
  PGPASSWORD         modes that touch one live database (dump/list/plan/apply)
  PGPASSWORD1/2      dbplan (two live databases)"""

// =============================================================================
// Well-factored Groovy API.  The command-line modes are thin shells over these
// methods; the "plan"/"apply" modes reuse the same primitives the script uses
// elsewhere (a temp database is handed to reconcileTables directly, not re-run
// through the CLI).
// =============================================================================

// ---- identifiers & connections -----------------------------------------------

String qi(String s) { '"' + s.replace('"', '""') + '"' }               // quote identifier
String qname(String schema, String table) { qi(schema) + '.' + qi(table) }
String dbUrl(String host, int port, String db) {
    "jdbc:postgresql://$host:$port/$db"
}
Sql connect(String host, int port, String db, String user, String pw) {
    Sql.newInstance(dbUrl(host, port, db), user, pw, 'org.postgresql.Driver')
}
Sql connectPg(String host, int port, String user, String pw) {
    connect(host, port, 'postgres', user, pw)      // admin connection for create/drop
}

// -- catalog metadata ----------------------------------------------------------

List<String> listTables(Sql db, String schema) {
    db.rows("""SELECT table_name FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                ORDER BY table_name""", [schema]).collect { it.table_name }
}

List<String> nonEmptyTables(Sql db, String schema) {
    listTables(db, schema).findAll { table ->
        (db.firstRow("SELECT count(*) AS n FROM " + qname(schema, table)).n as long) > 0
    }
}

// Primary key columns, in key order (supports composite keys).
List<String> primaryKey(Sql db, String schema, String table) {
    db.rows("""
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON kcu.constraint_name   = tc.constraint_name
         AND kcu.constraint_schema = tc.constraint_schema
        WHERE tc.constraint_type   = 'PRIMARY KEY'
          AND tc.table_schema = ?
          AND tc.table_name   = ?
        ORDER BY kcu.ordinal_position
    """, [schema, table]).collect { it.column_name }
}

// column name -> Postgres type name (as JDBC reports it), for literal formatting.
Map<String, String> columnTypes(Sql db, String schema, String table) {
    def map = [:] as Map
    def st = db.connection.createStatement()
    def rs = st.executeQuery("SELECT * FROM " + qname(schema, table) + " WHERE 1 = 0")
    try {
        def md = rs.getMetaData()
        (1..md.getColumnCount()).each { i -> map[md.getColumnLabel(i)] = md.getColumnTypeName(i) }
    } finally { rs.close(); st.close() }
    map
}

boolean tableExists(Sql db, String schema, String table) {
    db.firstRow("""SELECT 1 FROM information_schema.tables
                    WHERE table_schema = ? AND table_name = ? AND table_type = 'BASE TABLE'""",
        [schema, table]) != null
}

List<Map> rowsOf(Sql db, String schema, String table) {
    db.rows("SELECT * FROM " + qname(schema, table))
}

// -- external PG tools (for schema reconstruction and psql execution) -------------
// We use the standard client tools so the temp database carries the *full*
// constraint set (NOT NULL, UNIQUE, defaults, PK) exactly as the source does.
// That fidelity matters when a generated plan is tested against a copy of
// production data. pg_dump --schema-only gives us a reliable, complete DDL.

// Run an external process, forwarding PGPASSWORD, and return [exitCode, stdout].
// When `outFile` is given, stdout is streamed to that file instead of captured.
List runCmd(List cmd, String outFile = null) {
    def pb = new ProcessBuilder(cmd)
    pb.environment().put('PGPASSWORD', System.getenv('PGPASSWORD'))
    if (outFile) pb.redirectOutput(new File(outFile))
    def p = pb.start()
    def out = outFile ? '' : p.inputStream.text
    def err = p.errorStream.text
    def code = p.waitFor()
    if (code != 0) { System.err.println("command failed ($code): ${cmd.join(' ')}\n$err") }
    [code, out]
}

// Dump the schema-only DDL of the given tables (uses the -t flag per table) to
// a file, using pg_dump against the live database.
void dumpSchemaOnly(String host, String user, String db, List<String> tables, String outFile) {
    def cmd = ['pg_dump', '--schema-only', '-h', hostOf(host), '-p', portOf(host).toString(),
               '-U', user, '--dbname', db]
    tables.each { cmd += ['-t', it] }
    def res = runCmd(cmd)
    if (res[0] != 0) System.exit(1)
    new File(outFile).write(res[1])
}

// Recreate a set of tables (from a schema-only dump) in the temp database.
void loadSchemaDump(String host, String user, String db, List<String> tables, String tmpDb, String tmpDir) {
    def schemaFile = (tmpDir + '/schema.sql') as String
    dumpSchemaOnly(host, user, db, tables, schemaFile)
    def pb = new ProcessBuilder(['psql', '-h', hostOf(host), '-p', portOf(host).toString(),
                                 '-U', user, '-f', schemaFile, tmpDb])
    pb.environment().put('PGPASSWORD', System.getenv('PGPASSWORD'))
    def p = pb.start()
    def out = p.inputStream.text
    def err = p.errorStream.text
    def code = p.waitFor()
    if (code != 0) { System.err.println("psql failed loading schema ($code)\n$err"); System.exit(1) }
}

// Clone the FULL data (not just schema) of the given tables into a temp database,
// so the plan can be tested against a replica close to production. Uses --no-owner
// / --no-privileges so it loads cleanly into a database owned by `user`.
void loadFullData(String host, String user, String db, List<String> tables, String tmpDb, String tmpFile) {
    def cmd = ['pg_dump', '--no-owner', '--no-privileges', '-h', hostOf(host),
               '-p', portOf(host).toString(), '-U', user, '--dbname', db]
    tables.each { cmd += ['-t', it] }
    def res = runCmd(cmd, tmpFile)          // stdout -> data.sql file
    if (res[0] != 0) System.exit(1)
    def pb = new ProcessBuilder(['psql', '-h', hostOf(host), '-p', portOf(host).toString(),
                                 '-U', user, '-f', tmpFile, tmpDb])
    pb.environment().put('PGPASSWORD', System.getenv('PGPASSWORD'))
    def p = pb.start()
    def out = p.inputStream.text; def err = p.errorStream.text; def code = p.waitFor()
    if (code != 0) { System.err.println("psql failed loading full data ($code)\n$err"); System.exit(1) }
}

// Run psql against a database with a SQL script file, letting errors surface.
// Returns [exitCode, logPath]. The log is written to a *persistent* temp file
// (not the auto-cleaned TMP_DIR) so a caller can point the user at it on failure.
List applyScriptPsql(String host, String user, String db, String scriptFile) {
    def logFile = File.createTempFile('pgdata_apply', '.log')
    def pb = new ProcessBuilder(['psql', '-h', hostOf(host), '-p', portOf(host).toString(),
                                 '-U', user, '-v', 'ON_ERROR_STOP=1', '-f', scriptFile, db])
    pb.environment().put('PGPASSWORD', System.getenv('PGPASSWORD'))
    pb.redirectErrorStream(true)
    def p = pb.start()
    def output = p.inputStream.text
    def code = p.waitFor()
    logFile.text = output
    // Only keep the log when the script failed (so a caller can read it); on a
    // successful apply there is nothing to diagnose, so don't litter /tmp.
    if (code == 0) logFile.delete()
    [code, output, logFile.absolutePath]
}

// -- comparison (for the plan test phase) -----------------------------------------

// Compare two tables' rows (by primary key) and return human-readable, per-record
// difference messages. `a` is the desired/reference table, `b` the applied one.
List<String> compareTables(String schema, String table, List<String> pkCols,
                           List<Map> rowsA, List<Map> rowsB) {
    def diffs = []
    def sig = { r -> pkCols ? pkCols.collect { String.valueOf(r[it]) }.join('|') : (r.values().join('|')) }
    def keyLabel = { r -> pkCols.collect { "$it=${lit('', r[it])}" }.join(', ') }
    // Clean value rendering (GroovyRowResult.toString appends a spurious [c]);
    // format as a SQL literal so values are obvious in the message.
    def show = { r, c -> lit('', r[c]) }
    def aByPk = [:]; rowsA.each { aByPk[sig(it)] = it }
    def bByPk = [:]; rowsB.each { bByPk[sig(it)] = it }
    def t = "$schema.$table"
    aByPk.each { k, ra ->
        def rb = bByPk[k]

        if (rb == null) {
            diffs << "$t: record absent after apply (key ${keyLabel(ra)})"
        } else {
            ra.keySet().findAll { c -> !sameValue(ra[c], rb[c]) }.each { c ->
                diffs << "$t: column $c differs on key ${keyLabel(ra)}: applied=${show(rb,c)} expected=${show(ra,c)}"
            }
        }
    }
    bByPk.each { k, rb ->
        if (!aByPk.containsKey(k)) diffs << "$t: unexpected record in applied database (key ${keyLabel(rb)})"
    }
    diffs
}

// Compare two live databases table-by-table (only tables present on the source).
List<String> compareDbs(Sql a, Sql b, String schema) {
    def diffs = []
    listTables(a, schema).toSorted().each { table ->
        def rowsA = rowsOf(a, schema, table)
        if (!rowsA) return
        def rowsB = rowsOf(b, schema, table)
        def pk = primaryKey(a, schema, table)
        diffs.addAll(compareTables(schema, table, pk, rowsA, rowsB))
    }
    diffs
}

// The plan *test phase*: clone the full data of the target into a fresh temp
// database, apply `planFile` to that clone, then verify the clone matches the
// reference database `refSql` (the desired final state). Prints test
// confirmation or failure messages and uses the process exit code to signal.
void testPhase(String host, String user, String liveDb, String schema, String tmpDir,
               List<String> tables, Sql refSql, String planFile) {
    def clone = makeTempDbName()
    def dataFile = (tmpDir + '/full_' + System.currentTimeMillis() + '.sql') as String
    createDb(host, user, clone)
    try {
        loadFullData(host, user, liveDb, tables, clone, dataFile)

        // Apply the plan to the replica. Capture + keep the log on failure so the
        // user can read exactly what went wrong.
        def applied = applyScriptPsql(host, user, clone, planFile)
        if (applied[0] != 0) {
            System.err.println('TEST FAILED: the plan script failed to apply to a copy of the database.')
            System.err.println("Full psql output preserved at: ${applied[2]}")
            System.err.println(applied[1])
            throw new TestFailedException(applied[2])
        }

        def cloneSql = connect(hostOf(host), portOf(host), clone, user, System.getenv('PGPASSWORD'))
        def diffs = compareDbs(refSql, cloneSql, schema)
        cloneSql.close()

        if (diffs) {
            System.err.println("TEST FAILED: applying the plan to a copy of '$liveDb' did not produce the reference data.")
            if (diffs.size() <= 25) { diffs.each { System.err.println(it) } }
            else { diffs.take(25).each { System.err.println(it) }; System.err.println("... and ${diffs.size() - 25} more differences") }
            throw new TestFailedException()
        }
    } finally {
        dropDb(host, user, clone)
        new File(dataFile).delete()
    }
    println("TEST PASSED: plan applied to a copy of '$liveDb' and matches the reference data.")
}

// Thrown by testPhase on verification failure; the caller's finally still runs.
class TestFailedException extends RuntimeException {
    String logPath
    TestFailedException(String logPath = null) { this.logPath = logPath }
    TestFailedException() { }
}

// The BEGINNING of `plan` (also used by `test`): spin up the first temp database.
// Recreate the target schema (pg_dump --schema-only) for `tables` in a fresh db
// and load the reference INSERTs into it. Returns [sql, name] so the caller can
// use the staging handle and, in a finally, drop the db by name.
Map buildStaging(String host, String user, String liveDb, String schema, String tmpDir,
                 List<String> tables, String referenceFile) {
    def name = makeTempDbName()
    createDb(host, user, name)
    def sql = connect(hostOf(host), portOf(host), name, user, System.getenv('PGPASSWORD'))
    try {
        loadSchemaDump(host, user, liveDb, tables, name, tmpDir)
        execSql(sql, new File(referenceFile).text)
    } catch (Throwable e) {
        sql.close(); dropDb(host, user, name); throw e
    }
    [sql: sql, name: name]
}

// -- value formatting -------------------------------------------------------------

String lit(String typ, v) {
    if (v == null) return 'NULL'
    if (v instanceof Boolean) return v ? 'TRUE' : 'FALSE'
    if (v instanceof byte[]) return "decode('${v.encodeHex().toString()}', 'hex')"
    if (v instanceof Integer || v instanceof Long || v instanceof Short) return v.toString()
    if (v instanceof java.math.BigDecimal || v instanceof java.math.BigInteger) return v.toString()
    if (v instanceof Float || v instanceof Double) {
        def d = v.doubleValue()
        if (d.isNaN())    return "'NaN'::float8"
        if (d.isInfinite()) return d > 0 ? "'Infinity'::float8" : "'-Infinity'::float8"
        return v.toString()
    }
    if (v instanceof java.sql.Date) return "'" + v.toString() + "'"
    if (v instanceof java.sql.Time) return "'" + v.toString() + "'"
    if (v instanceof java.sql.Timestamp) {
        def tn = (typ ?: '').toLowerCase()
        if (tn.contains('time zone') || tn == 'timestamptz') {
            // explicit-offset literal so the instant round-trips in any session tz
            return "'" + v.toInstant().toString() + "'::timestamptz"
        }
        return "'" + v.toLocalDateTime() + "'"
    }
    // text / varchar / char / uuid / json / jsonb / inet / etc.
    "'" + v.toString().replace("'", "''") + "'"
}

boolean sameValue(a, b) {
    if (a == null) return b == null
    if (b == null) return false
    if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals(a as byte[], b as byte[])
    if (a instanceof Number && b instanceof Number) return a.compareTo(b) == 0
    a.equals(b)
}

String whereOnPk(Map colTypes, List pkCols, row) {
    pkCols.collect { c -> "$c = ${lit(colTypes[c], row[c])}" }.join(' AND ')
}
String insertInto(Map colTypes, String table, row) {
    def cols = row.keySet().toList()
    def vals = cols.collect { lit(colTypes[it], row[it]) }
    "INSERT INTO $table (${cols.join(', ')}) VALUES (${vals.join(', ')});"
}
String updateFrom(Map colTypes, String table, List pkCols, row, List diffCols) {
    def setClause = diffCols.collect { c -> "$c = ${lit(colTypes[c], row[c])}" }.join(', ')
    "UPDATE $table SET $setClause WHERE ${whereOnPk(colTypes, pkCols, row)};"
}
String deleteFrom(Map colTypes, String table, List pkCols, row) {
    "DELETE FROM $table WHERE ${whereOnPk(colTypes, pkCols, row)};"
}

// -- SQL script execution ------------------------------------------------------

// Split SQL on top-level semicolons, honoring single-quoted string literals
// (with '' escapes). Handles the INSERT/DDL scripts we produce and hand-edited
// reference files; not a general-purpose SQL parser.
List<String> splitStatements(String text) {
    def out = []
    def sb = new StringBuilder()
    boolean q = false
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i)
        if (q) {
            sb.append(c)
            if (c == "'") {
                if (i + 1 < text.length() && text.charAt(i + 1) == "'") { sb.append("'"); i++ }
                else q = false
            }
        } else if (c == "'") {
            q = true; sb.append(c)
        } else if (c == ';') {
            out << sb.toString(); sb = new StringBuilder()
        } else sb.append(c)
    }
    def tail = sb.toString().trim()
    if (tail && !(tail =~ /(?ims)^--.*$/)) out << tail
    out
}

void execSql(Sql db, String script) {
    splitStatements(script).each { stmt ->
        // A chunk may begin with a header comment (e.g. "-- Changes for table x")
        // followed by the actual INSERT on the next line. Drop leading comment
        // lines and execute the remainder, so the first statement after a header
        // is not silently discarded.
        def sql = stmt.readLines().findAll { !(it =~ /^\s*--/) }.join('\n').trim()
        if (sql && !(sql =~ /(?i)^--/)) db.execute(sql)
    }
}

// -- temp database lifecycle -----------------------------------------------------

String makeTempDbName() {
    "tmp_pgdata_${System.currentTimeMillis()}_${(int)(Math.random() * 100000)}"
}

void createDb(String host, String user, String dbName) {
    def res = runCmd(['createdb', '-h', hostOf(host), '-p', portOf(host).toString(), '-U', user, dbName])
    if (res[0] != 0) System.exit(1)
}
void dropDb(String host, String user, String dbName) {
    // --force severs any lingering sessions so the drop always succeeds.
    def res = runCmd(['dropdb', '-h', hostOf(host), '-p', portOf(host).toString(), '-U', user, '--force', dbName])
    if (res[0] != 0) System.exit(1)
}

// -- core reconciliation (the well-factored API) ---------------------------------

// Pure: given source rows and target rows for one table, append the INSERT /
// UPDATE / DELETE statements that bring the target in line with the source,
// keyed on the primary key. No DB access.
void reconcileTable(StringBuilder out, String table, Map colTypes, List pkCols,
                    Collection srcRows, Collection tgtRows) {
    def sig = { r -> pkCols.collect { String.valueOf(r[it]) }.join(' ') }
    def srcByPk = [:]; srcRows.each { r -> srcByPk[sig(r)] = r }
    def tgtByPk = [:]; tgtRows.each { r -> tgtByPk[sig(r)] = r }

    srcRows.each { r ->
        def s = sig(r)
        def t = tgtByPk[s]
        if (t == null) {
            out << insertInto(colTypes, table, r) << '\n'
        } else {
            def diff = r.keySet().findAll { c -> !sameValue(r[c], t[c]) } as List
            if (diff) out << updateFrom(colTypes, table, pkCols, r, diff) << '\n'
        }
    }
    tgtRows.each { r ->
        if (!srcByPk.containsKey(sig(r))) out << deleteFrom(colTypes, table, pkCols, r) << '\n'
    }
}

// =============================================================================
// Command-line interface: thin shells over the API above.
// =============================================================================

// -- argument parsing -----------------------------------------------------------
def argsList = args as List
def MODES = ['list', 'dump', 'plan', 'dbplan', 'apply', 'test'] as Set
def mode = (argsList && argsList[0] in MODES) ? argsList[0] : 'plan'
def rest = (argsList && argsList[0] in MODES) ? argsList[1..-1] : argsList

def opts = [:]
def allowed = ['--host', '--db', '--user', '--schema', '--host1', '--db1', '--user1', '--schema1',
               '--host2', '--db2', '--user2', '--schema2', '--file', '--output', '--plan'] as Set
def tableFilter = [] as List
for (int i = 0; i < rest.size(); i++) {
    if (rest[i] == '--') { tableFilter = rest[(i + 1)..-1]; break }
    if (rest[i] in allowed && i + 1 < rest.size()) { opts[rest[i]] = rest[i + 1]; i++ }
}

def TMP_DIR = (File.createTempDir())?.absolutePath ?: System.getProperty('java.io.tmpdir')
addShutdownHook { File dir = new File("$TMP_DIR"); if (dir.exists()) dir.deleteDir() }

int portOf(String s) { s && s.contains(':') ? (s.split(':')[1] as int) : 5432 }
String hostOf(String s) { s && s.contains(':') ? s.split(':')[0] : s }
def g = { String f -> opts[f] }
def need = { String f ->
    if (!opts['--' + f]) { System.err.println("missing required option --$f\n$CLI"); System.exit(1) }
}
// Connect one side: prefix '' -> --host/--db/--user; '1'/'2' -> the dbplan side set.
def side = { String prefix, String pw ->
    def h  = g("--host$prefix") ? g("--host$prefix") : g('--host')
    def d  = g("--db$prefix")   ? g("--db$prefix")   : g('--db')
    def u  = g("--user$prefix") ? g("--user$prefix") : g('--user')
    connect(hostOf(h), portOf(h), d, u, pw)
}
def effectiveTables = { Sql db, String schema ->
    tableFilter ? tableFilter : nonEmptyTables(db, schema)
}
List<Map> targetRows(Sql db, String schema, String table) {
    tableExists(db, schema, table) ? rowsOf(db, schema, table) : []
}

// ---- mode: list ----------------------------------------------------------------
if (mode == 'list') {
    ['host', 'db', 'user'].each { need(it) }
    def db = side('', System.getenv('PGPASSWORD'))
    nonEmptyTables(db, g('schema') ?: 'public').each { println it }
    db.close(); System.exit(0)
}

// ---- mode: dump ----------------------------------------------------------------
if (mode == 'dump') {
    ['host', 'db', 'user'].each { need(it) }
    def schema = g('schema') ?: 'public'
    def db = side('', System.getenv('PGPASSWORD'))
    def out = new StringBuilder()
    effectiveTables(db, schema).toSorted().each { table ->
        def rows = rowsOf(db, schema, table)
        if (!rows) return
        out << "-- Changes for table $table\n"
        def colT = columnTypes(db, schema, table)
        rows.each { out << insertInto(colT, qname(schema, table), it) << '\n' }
    }
    out << '-- Done.\n'
    print out.toString()
    db.close(); System.exit(0)
}

// ---- mode: dbplan (two live databases) -----------------------------------------
if (mode == 'dbplan') {
    ['host1','db1','user1','host2','db2','user2'].each { need(it) }
    def pw1 = System.getenv('PGPASSWORD1'), pw2 = System.getenv('PGPASSWORD2')
    if (!pw1 || !pw2) { System.err.println("dbplan requires PGPASSWORD1 and PGPASSWORD2\n$CLI"); System.exit(1) }
    def s1 = g('schema1') ?: 'public', s2 = g('schema2') ?: 'public'
    def db1 = side('1', pw1), db2 = side('2', pw2)
    def out = new StringBuilder()
    effectiveTables(db1, s1).toSorted().each { table ->
        def rows1 = rowsOf(db1, s1, table)
        if (!rows1) return
        out << "-- Changes for table $table\n"
        def colT = columnTypes(db1, s1, table)
        def pk = primaryKey(db1, s1, table)
        def rows2 = targetRows(db2, s2, table)
        reconcileTable(out, qname(s2, table), colT, pk, rows1, rows2)
    }
    out << '-- Done.\n'
    print out.toString()
    db1.close(); db2.close(); System.exit(0)
}

// ---- mode: apply ----------------------------------------------------------------
if (mode == 'apply') {
    ['host', 'db', 'user'].each { need(it) }
    def db = side('', System.getenv('PGPASSWORD'))
    if (g('--plan')) {
        execSql(db, new File(g('--plan')).text)
    } else if (g('--file')) {
        // plan + apply in one step
        def schema = g('--schema') ?: 'public'
        String h = g('--host')
        def tmp = makeTempDbName()
        createDb(h, g('--user'), tmp)
        try {
            // reproduce the target schema (full constraints) via pg_dump
            loadSchemaDump(h, g('--user'), g('--db'), effectiveTables(db, schema), tmp, TMP_DIR)
            def td = connect(hostOf(h), portOf(h), tmp, g('--user'), System.getenv('PGPASSWORD'))
            // load the reference INSERT script into the temp source
            execSql(td, new File(g('--file')).text)
            // reconcile: temp (source) onto target (real db), apply directly
            def plan = new StringBuilder()
            effectiveTables(td, schema).toSorted().each { table ->
                def rows = rowsOf(td, schema, table)
                if (!rows) return
                plan << "-- Changes for table $table\n"
                def colT = columnTypes(td, schema, table)
                def pk = primaryKey(td, schema, table)
                reconcileTable(plan, qname(schema, table), colT, pk, rows, rowsOf(db, schema, table))
            }
            execSql(db, plan.toString())
            td.close()
        } finally {
            dropDb(h, g('--user'), tmp)
        }
    } else {
        System.err.println("apply requires --plan or --file\n$CLI"); System.exit(1)
    }
    db.close(); System.exit(0)
}

// ---- mode: plan (script -> database) -------------------------------------------
// Build a staging reference in a temp db from the reference INSERTs, reconcile it
// against the target to produce the change script (written to --output), then run
// the test phase to confirm the plan applies cleanly to a full copy of the target.
if (mode == 'plan') {
    ['host', 'db', 'user', 'file', 'output'].each { need(it) }
    def schema = g('--schema') ?: 'public'
    def pPath = g('--output')
    String h = g('--host')
    def pw = System.getenv('PGPASSWORD')
    def db = side('', pw)
    // BEGINNING: the first (staging) temp database = reference data.
    def st = buildStaging(h, g('--user'), g('--db'), schema, TMP_DIR, effectiveTables(db, schema), g('--file'))
    try {
        def td = st.sql
        def out = new StringBuilder()
        effectiveTables(td, schema).toSorted().each { table ->
            def srcRows = rowsOf(td, schema, table)
            if (!srcRows) return
            out << "-- Changes for table $table\n"
            def colT = columnTypes(td, schema, table)
            def pk = primaryKey(td, schema, table)
            def tgtRows = targetRows(db, schema, table)
            reconcileTable(out, qname(schema, table), colT, pk, srcRows, tgtRows)
        }
        out << '-- Done.\n'
        new File(pPath).write(out.toString())
        System.out.println("Plan written to: $pPath (${out.length()} bytes)")

        // END: the test phase — confirm the plan applies to a full copy of the target.
        testPhase(h, g('--user'), g('--db'), schema, TMP_DIR, effectiveTables(td, schema), td, pPath)
    } catch (TestFailedException e) {
        // testPhase has already printed the details; ensure temp DBs are cleaned.
        st.sql.close()
        dropDb(h, g('--user'), st.name)
        System.exit(1)
    } finally {
        st.sql.close()
        dropDb(h, g('--user'), st.name)
    }
    db.close(); System.exit(0)
}

// ---- mode: test (run only the plan test phase) ----------------------------------
// Reuses exactly the two facts of `plan` it needs: the BEGINNING (build the
// staging reference from --file) and the END (verify --plan against a full copy),
// skipping the plan-generation part in the middle.
if (mode == 'test') {
    ['host', 'db', 'user', 'plan', 'file'].each { need(it) }
    def schema = g('--schema') ?: 'public'
    def pPath = g('--plan')
    String h = g('--host')
    def pw = System.getenv('PGPASSWORD')
    def db = side('', pw)
    def st = buildStaging(h, g('--user'), g('--db'), schema, TMP_DIR, effectiveTables(db, schema), g('--file'))
    try {
        testPhase(h, g('--user'), g('--db'), schema, TMP_DIR, effectiveTables(st.sql, schema), st.sql, pPath)
    } catch (TestFailedException e) {
        st.sql.close()
        dropDb(h, g('--user'), st.name)
        System.exit(1)
    } finally {
        st.sql.close()
        dropDb(h, g('--user'), st.name)
    }
    db.close(); System.exit(0)
}

System.err.println("unknown mode: $mode\n$CLI")
System.exit(1)
