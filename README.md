# NanoSplit

Splits a huge, generated SQL Server script (the kind SSMS's "Generate Scripts"
wizard or a GeneXus KB export produces - one `INSERT` per line, no semicolons,
batches separated by `GO`) into a set of smaller, independently runnable
parts, then drives their execution against SQL Server with progress tracking
and resume-from-failure.

Built for the case where the file is too big to open in SSMS at all - the one
this was built against is 10+ GB with 20+ million `INSERT` statements.

## Why not just run the .sql file directly?

You can, with `sqlcmd -i script.sql`. Two problems show up in practice once
the file gets big:

- **A single bad statement (or a dropped connection) loses everything** after
  it. There is no way to know how far it got, and no way to resume - you
  restart from the top.
- **SSMS won't even open a multi-GB file**, so you cannot skim it, edit it, or
  hand a piece of it to someone else.

NanoSplit cuts the script into parts of a bounded size, records exactly what
is in each part (byte offsets, table names, row counts, a checksum), and
tracks execution progress part by part and statement by statement - so a
failure tells you precisely where it happened, and re-running picks up from
there instead of from the beginning.

## How it works

1. **`split`** streams the input file once (never loading more than one
   statement into memory - this is what makes a 10 GB file cost about the
   same as a 10 MB one) and cuts it into parts under `output.dir`, alongside
   an `index.json` describing every part.
2. **`smoke`** tries just the first and last statement of every part (each
   rolled back by default) - a schema mismatch or a bad connection string
   surfaces in seconds instead of after loading millions of rows.
3. **`run`** executes every part in order against the configured database,
   saving progress after every commit. If it stops - a bad statement, a
   dropped connection, you hitting Ctrl+C - running it again resumes exactly
   from the statement it stopped on, not from part 1 and not by skipping a
   whole part.
4. **`status`** / **`verify`** answer "where did it stop?" and "are these
   part files still intact?" without re-reading the whole thing.

### Session context survives the cut

The hard part of splitting a generated dump isn't the cutting - it's that a
cut loses session state. `USE [db]` appears once, at the top of a 10 GB file;
`SET IDENTITY_INSERT [table] ON` appears once per table. A part that starts
with a bare `INSERT` would run against the wrong database, or get rejected
for writing an identity column.

NanoSplit tracks these statements as it scans (`split.contextPattern`,
default: `USE`, `SET IDENTITY_INSERT` and the other common `SET` options) and
replays them at the top of every part that needs them, closing any
`IDENTITY_INSERT` left open at the bottom. Each part is independently
runnable - you can hand `part_00042.sql` to someone else and it will run on
its own.

## Requirements

- **Java 8 (1.8.0_121) or newer** to run the built jar. Built and tested with
  a JDK 17 toolchain targeting bytecode level 8, so it also runs on older
  JREs some environments are stuck with.
- **SQL Server** reachable over **TCP/IP** - the Microsoft JDBC driver only
  speaks TCP/IP; a named pipe or shared-memory-only configuration (common on
  a fresh local SQL Server Express install) will not work. See
  [Troubleshooting](#troubleshooting) if `nanosplit run`/`smoke` can't
  connect to a local SQLEXPRESS instance.
- Maven 3.6+ if you want to build from source (`mvn package`).

## Quickstart

```bash
# 1. Build (or download nanosplit.jar from a release)
mvn package
# -> target/nanosplit.jar

# 2. Create your local config (never committed - see .gitignore)
java -jar target/nanosplit.jar init
# edit the generated nanosplit.properties: input.file, db.server, db.name, ...

# 3. Cut the file into parts
java -jar target/nanosplit.jar split

# 4. Sanity-check the first/last row of every part against your schema
java -jar target/nanosplit.jar smoke

# 5. Run them all, with resume-on-failure
java -jar target/nanosplit.jar run

# Anytime: where did it stop?
java -jar target/nanosplit.jar status
```

Every command accepts `--set key=value` (repeatable) to override any config
key for that one run without editing the file, e.g.:

```bash
java -jar target/nanosplit.jar split --set split.maxBytesPerPart=32MB --force
```

## Configuration

NanoSplit reads `nanosplit.properties` (Java properties format) from the
current directory by default, or from `--config <path>`. Every key has a
built-in default - `nanosplit init` writes a commented template
(`src/main/resources/nanosplit.properties.example` in this repo) with the
common ones spelled out.

Precedence, lowest to highest: **built-in default** → **`nanosplit.properties`**
→ **`NANOSPLIT_*` environment variable** → **`--set key=value`**.

An environment variable overrides any key: `db.password` becomes
`NANOSPLIT_DB_PASSWORD`, `split.maxBytesPerPart` becomes
`NANOSPLIT_SPLIT_MAXBYTESPERPART`. Prefer this over typing a password into
the properties file.

Windows paths in the properties file: either double the backslashes
(`C:\\Program Files\\...`) or use forward slashes (`C:/Program Files/...`,
which SQL Server and Java both accept fine). A single backslash before a
letter that isn't `n`, `r`, `t`, `f`, or `u` is kept literally rather than
silently eaten, so a half-escaped path like `C:\Program Files\x` still works
- but doubling it is still the safer habit.

### Key settings

| Key | Default | Meaning |
|---|---|---|
| `db.server` | `localhost\SQLEXPRESS` | `host` or `host\instance` |
| `db.name` | | Database to connect to |
| `db.integratedSecurity` | `true` | Windows auth vs. `db.user`/`db.password` |
| `input.file` | | The script to split |
| `output.dir` | `temp/sql_parts` | Where parts, `index.json` and state go |
| `split.maxStatementsPerPart` | `50000` | Cut after this many statements... |
| `split.maxBytesPerPart` | `64MB` | ...or this many bytes, whichever first |
| `split.parts` | `0` | Or: force exactly N parts, ignoring the above |
| `run.commitEvery` | `1000` | Statements per transaction/commit |
| `run.stopOnError` | `true` | Stop the whole run vs. skip past a bad statement |
| `run.retries` | `1` | Attempts per part before giving up (1 = no retry) |

See `nanosplit.properties.example` for the full list with comments.

## Commands

| Command | Does |
|---|---|
| `init` | Write a local `nanosplit.properties` from the bundled template |
| `split` | Cut `input.file` into parts under `output.dir` |
| `smoke` | Try each part's first/last statement only, rolled back by default |
| `run` | Execute every part, tracking progress; resumes automatically on re-run |
| `status` | Show how many parts are done/pending/failed, and why |
| `verify` | Recompute every part's checksum against `index.json` |

Run `nanosplit <command> --help` for that command's full option list.

## What "resume" actually does

`run` records, after every commit, exactly how many statements of the current
part have landed in the database. If a part fails partway through:

- `nanosplit status` shows which part failed and the exact source line of the
  statement that caused it.
- Running `nanosplit run` again skips every part already marked done, and for
  the failed part, **starts from the statement after the last one that
  committed** - not from the top of the part, and not by skipping the whole
  part. Nothing gets inserted twice, nothing gets silently missed.

By default (`run.stopOnError=true`) a failure stops the whole run so you can
look at it. Set it to `false` to log the bad statement and keep going - it is
still recorded as failed in `status` either way.

## Troubleshooting

**`nanosplit run`/`smoke` can't connect ("SocketTimeoutException", "server not
found or not accessible", or a UDP/port-1434 message)**

The Microsoft JDBC driver connects over **TCP/IP only**. A local SQL Server
Express install frequently has TCP/IP disabled by default (Shared
Memory/Named Pipes only), which is why `sqlcmd`/SSMS can connect locally but
NanoSplit cannot. Fix: open **SQL Server Configuration Manager** → SQL Server
Network Configuration → Protocols for `<your instance>` → enable **TCP/IP** →
restart the SQL Server service. If you connect with `host\instance` (a named
instance), also start the **SQL Server Browser** service, or connect with a
fixed port instead (Configuration Manager → TCP/IP → IPAll → TCP Port) and
skip the instance name in `db.server`.

**Windows Authentication fails with a native library error**

`db.integratedSecurity=true` needs the mssql-jdbc auth DLL
(`mssql-jdbc_auth-<version>-<arch>.dll`, e.g. `x64`) on `PATH` or next to
`nanosplit.jar`. It ships inside the `mssql-jdbc` artifact; extract it from
the jar or download it from the
[driver's releases](https://github.com/microsoft/mssql-jdbc/releases). If
you'd rather not deal with it, switch to SQL Server authentication
(`db.integratedSecurity=false`, `db.user`, `db.password`), which needs no
native library.

**A Windows path in `nanosplit.properties` looks mangled**

See [Configuration](#configuration) above - double the backslashes or use
forward slashes.

## Building from source

```bash
mvn package        # -> target/nanosplit.jar (shaded, all dependencies included)
mvn test           # unit tests (no database required)
```

The jar is self-contained (picocli, Gson, and the SQL Server JDBC driver are
bundled) - `java -jar nanosplit.jar` is all you need on the target machine.

## Project layout

```
src/main/java/com/nanosplit/
  config/    .properties loading, precedence, path/type-safe accessors
  sql/       the streaming scanner (statement boundaries, context tracking)
  split/     turns scanned units into part files + index.json
  db/        JDBC connection, batched statement execution, resume logic
  index/     index.json / nanosplit-state.json data model and persistence
  cli/       picocli commands (init, split, smoke, run, status, verify)
```

## License

MIT - see [LICENSE](LICENSE).
