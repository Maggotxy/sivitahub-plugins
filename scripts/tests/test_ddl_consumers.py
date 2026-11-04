"""Behavioral positive/negative fixtures for the actual source-consumer gate."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("ddl_consumers", ROOT / "scripts/ddl-consumers.py")
assert SPEC and SPEC.loader
DDL = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = DDL
SPEC.loader.exec_module(DDL)


def consumer(body: str, fields: str = 'static final String RECEIPTS = "rpg_probe";',
             parameters: str = "Connection connection, SchemaInspector.Schema schema, String input") -> str:
    return f"class Consumer {{ {fields} void run({parameters}) {{ {body} }} }}"


class JavaSqlConsumersTest(unittest.TestCase):
    def test_actual_schema_qualified_read_and_write_are_consumers(self):
        for operation, suffix, execution in [("SELECT operation_id FROM ", " WHERE operation_id = ?", "executeQuery"),
                                              ("INSERT INTO ", " (operation_id) VALUES (?)", "executeUpdate")]:
            source = consumer('try (var query = connection.prepareStatement("' + operation
                              + '" + schema.tables().get(RECEIPTS).qualifiedName() + "' + suffix + '")) '
                              + '{ query.' + execution + '(); }')
            with self.subTest(operation=operation):
                self.assertEqual({"rpg_probe"}, DDL.scan_source(source))

    def test_multiline_text_block_and_string_concatenation_reach_execution(self):
        source = consumer('''
            String sql = """
                SELECT id, amount
                FROM rpg_probe
                WHERE id = ?
                """ + " ORDER BY id";
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                try (var rows = query.executeQuery()) { }
            }
        ''')
        self.assertEqual({"rpg_probe"}, DDL.scan_source(source))

    def test_local_alias_is_resolved_at_its_definition_not_at_later_reassignment(self):
        source = consumer('''
            String sql = "SELECT * FROM rpg_probe";
            String saved = sql;
            sql = input;
            try (var query = connection.prepareStatement(saved)) { query.executeQuery(); }
        ''')
        self.assertEqual({"rpg_probe"}, DDL.scan_source(source))

    def test_chained_prepared_execution_and_direct_statement_execution(self):
        for body in ['connection.prepareStatement("UPDATE rpg_probe SET x = ?").executeUpdate();',
                     'try (Statement statement = connection.createStatement()) { statement.execute("DELETE FROM rpg_probe"); }',
                     'connection.createStatement().executeQuery("SELECT * FROM rpg_probe");']:
            with self.subTest(body=body): self.assertEqual({"rpg_probe"}, DDL.scan_source(consumer(body)))

    def test_batch_requires_both_add_and_execute(self):
        cases = {
            'var q = connection.prepareStatement("INSERT INTO rpg_probe VALUES (?)"); q.addBatch(); q.executeBatch();': True,
            'var q = connection.prepareStatement("INSERT INTO rpg_probe VALUES (?)"); q.addBatch();': False,
            'var q = connection.prepareStatement("INSERT INTO rpg_probe VALUES (?)"); q.executeBatch();': False,
            'var q = connection.createStatement(); q.addBatch("INSERT INTO rpg_probe VALUES (1)"); q.executeBatch();': True,
        }
        for body, expected in cases.items():
            with self.subTest(body=body): self.assertEqual({"rpg_probe"} if expected else set(), DDL.scan_source(consumer(body)))

    def test_simple_helper_must_be_called_and_consumed(self):
        helper = 'String querySql() { return "SELECT * FROM rpg_probe"; }'
        used = consumer('connection.prepareStatement(querySql()).executeQuery();', helper)
        unused = consumer('String ignored = querySql();', helper)
        self.assertEqual({"rpg_probe"}, DDL.scan_source(used))
        self.assertEqual(set(), DDL.scan_source(unused))

    def test_isolated_constant_unused_sql_and_unexecuted_prepare_are_not_consumers(self):
        cases = ['', 'String unused = "SELECT * FROM rpg_probe";',
                 'String unused = "SELECT * FROM " + schema.tables().get(RECEIPTS).qualifiedName();',
                 'var unused = connection.prepareStatement("SELECT * FROM rpg_probe");',
                 'connection.prepareStatement("SELECT * FROM rpg_probe");',
                 'logger.info("SELECT * FROM rpg_probe");']
        for body in cases:
            with self.subTest(body=body): self.assertEqual(set(), DDL.scan_source(consumer(body)))

    def test_java_comments_quoted_fake_calls_and_unicode_comments_do_not_count(self):
        cases = [
            '// connection.prepareStatement("SELECT * FROM rpg_probe").executeQuery();\n',
            '/* connection.prepareStatement("SELECT * FROM rpg_probe").executeQuery(); */',
            r'\u002f\u002a connection.prepareStatement("SELECT * FROM rpg_probe").executeQuery(); \u002a\u002f',
            r'String unused = "connection.prepareStatement(\"SELECT * FROM rpg_probe\").executeQuery();";',
            'String unused = """\nconnection.prepareStatement("SELECT * FROM rpg_probe").executeQuery();\n""";',
        ]
        for body in cases:
            with self.subTest(body=body): self.assertEqual(set(), DDL.scan_source(consumer(body)))

    def test_sql_comments_values_and_quoted_keyword_aliases_are_not_tables(self):
        for sql in ["SELECT 'FROM rpg_probe'", "SELECT $$FROM rpg_probe$$", "SELECT $audit$FROM rpg_probe$audit$",
                    "SELECT 1 /* FROM rpg_probe */", "SELECT 1 -- FROM rpg_probe\n", 'SELECT "FROM" rpg_probe',
                    "SELECT 1 AS rpg_probe"]:
            body = 'connection.createStatement().executeQuery("""\n' + sql + '\n""");'
            with self.subTest(sql=sql): self.assertEqual(set(), DDL.scan_source(consumer(body)))

    def test_unknown_dynamic_table_cannot_borrow_an_unused_known_constant(self):
        cases = [
            'connection.prepareStatement("SELECT * FROM " + schema.tables().get(input).qualifiedName()).executeQuery();',
            'connection.prepareStatement("SELECT * FROM " + input).executeQuery();',
            'connection.prepareStatement("SELECT * FROM rpg_probe" + input).executeQuery();',
            'String table = RECEIPTS; table = input; connection.prepareStatement("SELECT * FROM " + table).executeQuery();',
            'String table = RECEIPTS; if (flag) { table = input; } connection.prepareStatement("SELECT * FROM " + table).executeQuery();',
        ]
        for body in cases:
            with self.subTest(body=body): self.assertEqual(set(), DDL.scan_source(consumer(body)))

    def test_parameter_and_lambda_shadowing_blocks_class_constant_reuse(self):
        body = 'connection.prepareStatement("SELECT * FROM " + schema.tables().get(RECEIPTS).qualifiedName()).executeQuery();'
        self.assertEqual(set(), DDL.scan_source(consumer(body,
            parameters="Connection connection, SchemaInspector.Schema schema, String RECEIPTS")))
        self.assertEqual(set(), DDL.scan_source(consumer('names.forEach(RECEIPTS -> {' + body + '});')))

    def test_unchecked_qualified_name_or_known_non_jdbc_receiver_does_not_count(self):
        body = 'connection.prepareStatement("SELECT * FROM " + schema.tables().get(RECEIPTS).qualifiedName()).executeQuery();'
        self.assertEqual(set(), DDL.scan_source(consumer(body, parameters="Connection connection, Object schema")))
        self.assertEqual(set(), DDL.scan_source(consumer(
            'Object fake = input; fake.prepareStatement("SELECT * FROM rpg_probe").executeQuery();')))
        self.assertEqual(set(), DDL.scan_source(consumer(
            'connection.prepareStatement("SELECT * FROM " + unreviewed(RECEIPTS).qualifiedName()).executeQuery();')))

    def test_two_methods_or_classes_cannot_share_unrelated_local_sql_bindings(self):
        source = '''class A {
            void unused() { String sql = "SELECT * FROM rpg_probe"; }
            void used(Connection connection, String sql) { connection.prepareStatement(sql).executeQuery(); }
        } class B {
            String querySql() { return "SELECT * FROM rpg_probe"; }
        } class C {
            String querySql() { return "SELECT 1"; }
            void used(Connection connection) { connection.prepareStatement(querySql()).executeQuery(); }
        }'''
        self.assertEqual(set(), DDL.scan_source(source))

    def test_identifier_boundaries_and_resolved_suffixes_are_exact(self):
        self.assertEqual({"rpg_probe_typo"}, DDL.scan_source(consumer(
            'connection.prepareStatement("SELECT * FROM rpg_probe" + "_typo").executeQuery();')))
        self.assertEqual({"rpg_probe2"}, DDL.scan_source(consumer(
            'connection.prepareStatement("SELECT * FROM rpg_probe" + "2").executeQuery();')))

    def test_literal_delimiters_and_sql_comment_markers_do_not_corrupt_java_lexing(self):
        self.assertEqual({"rpg_probe"}, DDL.scan_source(consumer('''
            String braces = "{" + ")" + ";";
            String sql = "SELECT * FROM rpg_probe WHERE note = '/*' AND suffix = ')'";
            connection.prepareStatement(sql).executeQuery();
        ''')))

    def test_schema_literal_lookup_and_statically_known_dialect_branches_work(self):
        self.assertEqual({"rpg_probe"}, DDL.scan_source(consumer('''
            String sql = isH2(connection) ? "MERGE INTO rpg_probe VALUES (?)" : "INSERT INTO rpg_probe VALUES (?)";
            connection.prepareStatement(sql).executeUpdate();
        ''')))
        self.assertEqual({"rpg_probe"}, DDL.scan_source(consumer('''
            connection.prepareStatement("SELECT * FROM " + schema.tables().get("rpg_probe").qualifiedName()).executeQuery();
        ''')))


class ShellConsumerGateTest(unittest.TestCase):
    def gate(self, source: str, language: str = "") -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="sivita-ddl-gate-") as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "tools").mkdir()
            java = root / "plugins/example/src/main/java"; java.mkdir(parents=True)
            resources = root / "plugins/example/src/main/resources"; resources.mkdir()
            canonical = root / "plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence"
            canonical.mkdir(parents=True)
            (canonical / "Database.java").write_text('class Database { String ddl = "CREATE TABLE IF NOT EXISTS rpg_probe (id INTEGER)"; }')
            (java / "Consumer.java").write_text(source)
            (resources / "plugin.yml").write_text("name: Example\ncommands:\n  example:\n    description: Example\n")
            if language:
                (resources / "lang").mkdir()
                (resources / "lang/en_us.yml").write_text(language)
            for name in ["check-ddl-consumers.sh", "ddl-consumers.py"]:
                shutil.copyfile(ROOT / "scripts" / name, root / "scripts" / name)
            return subprocess.run(["sh", "scripts/check-ddl-consumers.sh"], cwd=root, text=True,
                                  capture_output=True, timeout=20)

    def test_full_gate_accepts_real_multiline_schema_qualified_execution(self):
        result = self.gate(consumer('''
            try (var query = connection.prepareStatement("SELECT id FROM "
                + schema.tables().get(RECEIPTS).qualifiedName() + " WHERE id = ?")) {
                query.executeQuery();
            }
        '''))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("DDL_CONSUMER_GATE=GREEN", result.stdout)
        self.assertIn("DDL_REGISTRATION_GATE=GREEN", result.stdout)

    def test_full_gate_rejects_constant_comment_unused_sql_and_unknown_dynamic_table(self):
        for body in ['', '/* SELECT * FROM rpg_probe */', 'String unused = "SELECT * FROM rpg_probe";',
                     'connection.prepareStatement("SELECT * FROM " + input).executeQuery();']:
            with self.subTest(body=body):
                result = self.gate(consumer(body))
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn("[未接线] rpg_probe", result.stdout)

    def test_reverse_registration_still_rejects_unknown_resolved_dynamic_names(self):
        result = self.gate(consumer('''
            connection.prepareStatement("SELECT * FROM rpg_probe").executeQuery();
            connection.prepareStatement("SELECT * FROM rpg_probe" + "_typo").executeQuery();
        '''))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("DDL_REGISTRATION_GATE=RED", result.stdout)
        self.assertIn("rpg_probe_typo", result.stdout)

    def test_command_reference_failure_still_makes_the_gate_fail(self):
        result = self.gate(consumer('connection.prepareStatement("SELECT * FROM rpg_probe").executeQuery();'),
                           'help: "Use /missingcommand now"\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("CMD_REFERENCE_GATE=RED", result.stdout)


if __name__ == "__main__":
    unittest.main()
