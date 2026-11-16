"""Run the actual Kotlin migration/query SQL against SQLite without Android runtime dependencies."""

import ast
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
DAO = ROOT / "app/src/main/java/me/rerere/rikkahub/data/db/dao/UsageRecordDAO.kt"
MIGRATION = ROOT / "app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_32_33.kt"


def kotlin_string_expression(expression, constants):
    chunks, offset = [], 0
    for match in re.finditer(r'"(?:[^"\\]|\\.)*"|[A-Z][A-Z_0-9]*', expression):
        separator = expression[offset:match.start()].strip()
        if separator not in ("", "+"):
            raise AssertionError("Unexpected Kotlin SQL expression syntax")
        token = match.group()
        chunks.append(ast.literal_eval(token) if token.startswith('"') else constants[token])
        offset = match.end()
    if expression[offset:].strip():
        raise AssertionError("Unparsed Kotlin SQL expression suffix")
    return "".join(chunks)


def actual_totals_query():
    source = DAO.read_text()
    constants = {}
    for match in re.finditer(r'private const val (\w+) =\s*(.*?)(?=\n(?:private const val|@Dao))', source, re.S):
        constants[match[1]] = kotlin_string_expression(match[2], constants)
    expression = re.search(r'@Query\(\s*(.*?)\n    \)', source, re.S)[1]
    return kotlin_string_expression(expression, constants)


class CacheUsageDatabaseTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.execute("CREATE TABLE usage_record (id INTEGER PRIMARY KEY, created_at INTEGER NOT NULL, prompt_tokens INTEGER NOT NULL, completion_tokens INTEGER NOT NULL, cached_tokens INTEGER NOT NULL)")
        # Historical zeros could mean either no hit or no field; only positive counts prove reporting.
        self.db.execute("INSERT INTO usage_record VALUES (1, 1, 200, 10, 0)")
        self.db.execute("INSERT INTO usage_record VALUES (2, 2, 100, 10, 50)")
        for sql in re.findall(r'db\.execSQL\("([^"\n]*)"\)', MIGRATION.read_text()):
            self.db.execute(sql)

    def tearDown(self):
        self.db.close()

    def test_old_data_survives_and_zero_does_not_become_known(self):
        rows = self.db.execute("SELECT * FROM usage_record ORDER BY id").fetchall()
        self.assertEqual([(200, 10, 0), (100, 10, 50)], [(r["prompt_tokens"], r["completion_tokens"], r["cached_tokens"]) for r in rows])
        self.assertEqual([0, 1], [r["cached_tokens_reported"] for r in rows])
        self.assertTrue(all(r["cache_write_tokens"] is None for r in rows))

    def test_hit_rate_denominator_uses_only_matching_reported_samples(self):
        # One explicit zero and one wholly unreported cache sample are distinct after persistence.
        self.db.execute("INSERT INTO usage_record VALUES (3, 3, 100, 10, 0, 1, 1, 0)")
        self.db.execute("INSERT INTO usage_record VALUES (4, 4, 300, 10, 0, 1, 0, NULL)")
        row = self.db.execute(actual_totals_query(), {"since": 0}).fetchone()
        self.assertEqual(4, row["requestCount"])
        self.assertEqual(2, row["cacheReportedRequestCount"])
        self.assertEqual(200, row["cacheRatePromptTokens"])
        self.assertEqual(50, row["cacheRateCachedTokens"])
        self.assertEqual(25.0, row["cacheRateCachedTokens"] * 100.0 / row["cacheRatePromptTokens"])
        self.assertEqual(0, row["cacheWriteTokens"])
        self.assertEqual(1, row["cacheWriteReportedRequestCount"])

    def test_missing_or_invalid_input_does_not_fabricate_a_percentage(self):
        self.db.execute("INSERT INTO usage_record VALUES (3, 3, 0, 10, 100, 0, 1, NULL)")
        self.db.execute("INSERT INTO usage_record VALUES (4, 4, 10, 10, 50, 1, 1, NULL)")
        row = self.db.execute(actual_totals_query(), {"since": 3}).fetchone()
        self.assertEqual(2, row["requestCount"])
        self.assertEqual(0, row["cacheRateRequestCount"])
        self.assertEqual(0, row["cacheRatePromptTokens"])
        self.assertIsNone(row["cacheWriteTokens"])

    def test_empty_scope_has_no_known_zero_sample(self):
        row = self.db.execute(actual_totals_query(), {"since": 999}).fetchone()
        self.assertEqual(0, row["requestCount"])
        self.assertEqual(0, row["cacheReportedRequestCount"])
        self.assertEqual(0, row["cacheRateRequestCount"])
        self.assertIsNone(row["cacheWriteTokens"])


if __name__ == "__main__":
    unittest.main()
