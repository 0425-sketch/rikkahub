"""Exercise the production Kotlin migrations against real host SQLite.

The bridge supplies only Android/Room interfaces; SQL and PRAGMA queries execute on
Python's sqlite3 connection, including a real transaction. This does not replace
CompatibilityMigrationTest / BackupManagerTest on Android: Room's generated
validation and the Android restore entry point require a device.

Requires JAVA_HOME and a populated GRADLE_USER_HOME (or KOTLIN_BRIDGE_LIB pointing
to a Gradle distribution's lib directory). No dependency download is performed.
"""

import base64
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "app/schemas/me.rerere.rikkahub.data.db.AppDatabase"
MIGRATIONS = ROOT / "app/src/main/java/me/rerere/rikkahub/data/db/migrations"
SOURCES = {
    "original32": SCHEMAS / "32.json",
    "personal33": SCHEMAS / "33.json",
    "upstream33": ROOT / "app/src/androidTest/assets/database-history/upstream-33.json",
    "upstream34": SCHEMAS / "34.json",
}


def schema_of(path):
    return json.loads(path.read_text())["database"]


def create_schema(db, schema):
    for entity in schema["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    for sql in schema["setupQueries"]:
        db.execute(sql)
    db.execute(f'PRAGMA user_version={schema["version"]}')


def structure(db):
    """Compare columns, defaults, FKs and actual index columns, independent of order/hash."""
    result = {}
    for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'"):
        columns = sorted(tuple(row[1:]) for row in db.execute(f'PRAGMA table_info("{name}")'))
        fks = sorted(tuple(row[1:]) for row in db.execute(f'PRAGMA foreign_key_list("{name}")'))
        indexes = sorted((row[1], row[2], tuple(db.execute(f'PRAGMA index_info("{row[1]}")'))) for row in db.execute(f'PRAGMA index_list("{name}")'))
        result[name] = columns, fks, indexes
    return result


def seed(db, source):
    db.execute("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES ('chat', 'conversation survives', '[]', 1, 2)")
    for index, node_id in [(3, "last"), (0, "first"), (1, "middle"), (1, "same-index")]:
        db.execute("INSERT INTO message_node VALUES (?, 'chat', ?, '[]', 0)", (node_id, index))
    db.execute("INSERT INTO memory_file VALUES ('assistant', 'profile.md', 'memory survives', 9)")
    db.execute("INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at) VALUES ('workspace', 'workspace survives', '/workspace', 'DISABLED', 1, 2)")
    db.execute("INSERT INTO usage_record (created_at, source, prompt_tokens, completion_tokens, cached_tokens) VALUES (1, 'CHAT', 100, 10, 0), (2, 'CHAT', 100, 10, 50)")
    if source == "personal33":
        db.execute("UPDATE usage_record SET prompt_tokens_reported=1, cached_tokens_reported=1, cache_write_tokens=0 WHERE id=1")
        db.execute("UPDATE usage_record SET cache_write_tokens=25 WHERE id=2")
    if source == "upstream34":
        db.execute("UPDATE workspaces SET storage_mounts='[{\"path\":\"/shared\",\"writable\":false}]'")
    db.commit()


class KotlinMigrationBridge:
    """Compile unmodified production migration bodies, then serve their DB calls."""

    def __init__(self):
        self.temp = tempfile.TemporaryDirectory(prefix="schema35-kotlin-")
        directory = Path(self.temp.name)
        configured = os.environ.get("KOTLIN_BRIDGE_LIB")
        gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
        candidates = [Path(configured)] if configured else list(gradle_home.glob("wrapper/dists/*/*/*/lib"))
        self.lib = next((p for p in candidates if list(p.glob("kotlin-compiler-embeddable-*.jar"))), None)
        if self.lib is None:
            raise RuntimeError("Set GRADLE_USER_HOME or KOTLIN_BRIDGE_LIB to a cached Gradle distribution")
        self.java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
        self.classes = directory / "classes"
        stdlib = next(self.lib.glob("kotlin-stdlib-*.jar"))
        sources = {
            "Migration.kt": """package androidx.room.migration
import androidx.sqlite.db.SupportSQLiteDatabase
abstract class Migration(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(db: SupportSQLiteDatabase)
}
""",
            "Database.kt": """package androidx.sqlite.db
interface SupportSQLiteDatabase {
    fun execSQL(sql: String)
    fun query(sql: String): Cursor
}
interface Cursor : java.io.Closeable {
    fun getColumnIndexOrThrow(name: String): Int
    fun moveToNext(): Boolean
    fun getString(index: Int): String
}
""",
            "Tracker.kt": """package me.rerere.rikkahub.data.db
object DatabaseMigrationTracker {
    fun onMigrationStart(from: Int, to: Int) {}
    fun onMigrationEnd() {}
}
""",
            "Main.kt": """import androidx.sqlite.db.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.migrations.*
import java.util.Base64
private fun encode(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
private fun decode(value: String) = String(Base64.getDecoder().decode(value))
fun main(args: Array<String>) {
    val db = object : SupportSQLiteDatabase {
        override fun execSQL(sql: String) {
            println("E\\t" + encode(sql))
            check(readln() == "OK")
        }
        override fun query(sql: String): Cursor {
            println("Q\\t" + encode(sql))
            val names = readln().split('\\t').map(::decode)
            val rows = List(readln().toInt()) { readln().split('\\t').map(::decode) }
            return object : Cursor {
                var position = -1
                override fun getColumnIndexOrThrow(name: String) = names.indexOf(name).also { check(it >= 0) }
                override fun moveToNext(): Boolean = ++position < rows.size
                override fun getString(index: Int): String = rows[position][index]
                override fun close() {}
            }
        }
    }
    when (args.single().toInt()) {
        32 -> { Migration_32_33.migrate(db); Migration_33_35.migrate(db) }
        33 -> Migration_33_35.migrate(db)
        34 -> Migration_34_35.migrate(db)
        else -> error("Unsupported fixture version")
    }
    println("DONE")
}
""",
        }
        for name, text in sources.items():
            (directory / name).write_text(text)
        compiler = [self.java, "-cp", str(self.lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", str(stdlib), "-d", str(self.classes)]
        subprocess.run(compiler + [str(directory / name) for name in sources]
                       + [str(MIGRATIONS / "Migration_32_33.kt"), str(MIGRATIONS / "Migration_33_35.kt")],
                       check=True, capture_output=True, text=True)
        self.command = [self.java, "-cp", str(self.classes) + os.pathsep + str(stdlib), "MainKt"]

    def migrate(self, db):
        version = db.execute("PRAGMA user_version").fetchone()[0]
        if version not in (32, 33, 34):
            raise ValueError("Unsupported fixture version")
        process = subprocess.Popen(self.command + [str(version)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True)
        statements = []
        try:
            while True:
                line = process.stdout.readline().rstrip("\n")
                if line == "DONE":
                    break
                if not line:
                    raise AssertionError(process.stderr.read())
                kind, encoded = line.split("\t", 1)
                sql = base64.b64decode(encoded).decode()
                cursor = db.execute(sql)
                if kind == "E":
                    statements.append(sql)
                    response = "OK\n"
                else:
                    def encoded_row(row):
                        return "\t".join(base64.b64encode(str(value).encode()).decode() for value in row)
                    rows = cursor.fetchall()
                    response = encoded_row([column[0] for column in cursor.description]) + "\n" + str(len(rows)) + "\n"
                    response += "".join(encoded_row(row) + "\n" for row in rows)
                process.stdin.write(response)
                process.stdin.flush()
            process.stdin.close()
            if process.wait(timeout=10) != 0:
                raise AssertionError(process.stderr.read())
            return statements
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()
            process.stderr.close()
            if not process.stdin.closed:
                process.stdin.close()


class Schema35DatabaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bridge = KotlinMigrationBridge()
        # Expected tables combine the untouched released upstream 34 with personal usage fields.
        expected = schema_of(SCHEMAS / "34.json")
        usage = next(e for e in schema_of(SCHEMAS / "33.json")["entities"] if e["tableName"] == "usage_record")
        expected["entities"] = [usage if e["tableName"] == "usage_record" else e for e in expected["entities"]]
        db = sqlite3.connect(":memory:")
        create_schema(db, expected)
        cls.expected_structure = structure(db)
        db.close()

    @classmethod
    def tearDownClass(cls):
        cls.bridge.temp.cleanup()

    def assert_records(self, db, source):
        self.assertEqual([("conversation survives",)], db.execute("SELECT title FROM ConversationEntity").fetchall())
        self.assertEqual([("memory survives",)], db.execute("SELECT content FROM memory_file").fetchall())
        expected = [(100, 10, 0, 1, 1, 0), (100, 10, 50, 0, 0, 25)] if source == "personal33" else [(100, 10, 0, 1, 0, None), (100, 10, 50, 1, 1, None)]
        self.assertEqual(expected, db.execute("SELECT prompt_tokens, completion_tokens, cached_tokens, prompt_tokens_reported, cached_tokens_reported, cache_write_tokens FROM usage_record ORDER BY id").fetchall())
        mounts = '[{"path":"/shared","writable":false}]' if source == "upstream34" else "[]"
        self.assertEqual([(mounts,)], db.execute("SELECT storage_mounts FROM workspaces").fetchall())
        query = "SELECT id FROM message_node WHERE conversation_id='chat' ORDER BY node_index"
        self.assertEqual([("first",), ("middle",), ("same-index",), ("last",)], db.execute(query).fetchall())
        plan = repr(db.execute("EXPLAIN QUERY PLAN " + query).fetchall())
        self.assertIn("index_message_node_conversation_id_node_index", plan)
        self.assertNotIn("TEMP B-TREE", plan)
        self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())

    def migrate_and_validate(self, db):
        db.execute("BEGIN")
        try:
            statements = self.bridge.migrate(db)
            self.assertEqual(self.expected_structure, structure(db))
            # The host harness commits the version only after validating, as Room does on Android.
            db.execute("PRAGMA user_version=35")
            db.commit()
            return statements
        except BaseException:
            db.rollback()
            raise

    def direct_upgrade(self, source):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "live.db"
            with sqlite3.connect(file) as db:
                create_schema(db, schema_of(SOURCES[source]))
                seed(db, source)
                statements = self.migrate_and_validate(db)
                self.assert_records(db, source)
                if source == "personal33":
                    self.assertFalse(any("UPDATE usage_record" in sql for sql in statements))
            with sqlite3.connect(file) as reopened:
                self.assertEqual(35, reopened.execute("PRAGMA user_version").fetchone()[0])
                self.assert_records(reopened, source)

    def staging_upgrade(self, source):
        # Host staging-copy SQL checks. Android BackupManagerTest separately covers the production publish/apply path.
        with tempfile.TemporaryDirectory() as directory:
            live, original, staged = [Path(directory) / name for name in ("live.db", "archive.db", "staged.db")]
            live.write_bytes(b"existing live database is untouched")
            with sqlite3.connect(original) as db:
                create_schema(db, schema_of(SOURCES[source]))
                seed(db, source)
            before = original.read_bytes()
            shutil.copyfile(original, staged)
            with sqlite3.connect(staged) as db:
                self.migrate_and_validate(db)
                self.assert_records(db, source)
            self.assertEqual(before, original.read_bytes())
            self.assertEqual(b"existing live database is untouched", live.read_bytes())

    def test_direct_original32(self): self.direct_upgrade("original32")
    def test_direct_personal33(self): self.direct_upgrade("personal33")
    def test_direct_upstream33(self): self.direct_upgrade("upstream33")
    def test_direct_upstream34(self): self.direct_upgrade("upstream34")
    def test_staged_original32(self): self.staging_upgrade("original32")
    def test_staged_personal33(self): self.staging_upgrade("personal33")
    def test_staged_upstream33(self): self.staging_upgrade("upstream33")
    def test_staged_upstream34(self): self.staging_upgrade("upstream34")

    def test_invalid_schema_rolls_back_all_changes(self):
        with sqlite3.connect(":memory:") as db:
            create_schema(db, schema_of(SOURCES["upstream33"]))
            seed(db, "upstream33")
            db.execute("ALTER TABLE usage_record DROP COLUMN completion_tokens")
            db.commit()
            before = structure(db)
            with self.assertRaises(AssertionError):
                self.migrate_and_validate(db)
            self.assertEqual(before, structure(db))
            self.assertEqual(33, db.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual([("conversation survives",)], db.execute("SELECT title FROM ConversationEntity").fetchall())

    def test_generated_new_install_matches_migrations(self):
        path = SCHEMAS / "35.json"
        self.assertTrue(path.exists(), "Run the app KSP task to generate the real Room 35.json first")
        with sqlite3.connect(":memory:") as db:
            create_schema(db, schema_of(path))
            self.assertEqual(self.expected_structure, structure(db))


if __name__ == "__main__":
    unittest.main()
