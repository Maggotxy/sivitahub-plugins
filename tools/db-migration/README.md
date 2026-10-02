# SivitaHub database migration tool

This operator-only Java utility copies every table in the reviewed RpgCore migration manifest from a stopped, file-backed H2 database into an already initialized PostgreSQL schema. A source-contract test fails whenever RpgCore adds a table, unique key, or runtime performance index before this tool is updated.

Use the guarded wrapper:

```bash
server/bin/migrate-rpg-h2-to-postgresql.sh --help
```

The implementation intentionally lives outside every Paper plugin. It does not load Bukkit, does not maintain a second copy of RpgCore DDL, and does not modify source H2. Its checked-in table manifest is only a fail-closed ordering/coverage list; schema details are compared through JDBC metadata at runtime.

Verification includes per-table row count, primary-key digest, full-row digest, important monetary/numeric sums, required unique indexes, required non-unique runtime indexes, and duplicate-key queries. Target writes use one serializable transaction and a PostgreSQL advisory lock.

Full production procedure and PostgreSQL container test: [`docs/DATABASE_MIGRATION.md`](../../docs/DATABASE_MIGRATION.md).
