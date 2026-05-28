#!/usr/bin/env python3
"""
Unit tests for tools/validate_framework.py.

The validator is the framework's keystone — it gates commits, runs in CI, and
gets implicitly trusted. These tests catch regressions in the validator itself.

Run with:  python3 -m unittest tools.test_validate_framework  -v
Or:        python3 tools/test_validate_framework.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

# Import the validator module from the same dir as this test.
sys.path.insert(0, str(Path(__file__).parent))
import validate_framework as v


class FrontmatterParsingTests(unittest.TestCase):
    def test_parses_valid_frontmatter(self):
        text = "---\nname: foo\ndescription: bar\n---\nbody\n"
        fm, body_line = v.parse_frontmatter(text)
        self.assertEqual(fm["name"], "foo")
        self.assertEqual(fm["description"], "bar")
        self.assertGreaterEqual(body_line, 4)

    def test_returns_empty_dict_when_no_frontmatter(self):
        text = "no frontmatter here\nplain markdown\n"
        fm, body_line = v.parse_frontmatter(text)
        self.assertEqual(fm, {})
        self.assertEqual(body_line, 1)

    def test_returns_empty_dict_when_frontmatter_unterminated(self):
        text = "---\nname: foo\n(no closing fence)\n"
        fm, _ = v.parse_frontmatter(text)
        self.assertEqual(fm, {})

    def test_ignores_comments_in_frontmatter(self):
        text = "---\n# comment line\nname: foo\n---\nbody\n"
        fm, _ = v.parse_frontmatter(text)
        self.assertEqual(fm, {"name": "foo"})

    def test_handles_value_containing_colons(self):
        text = "---\nurl: https://example.com:8080\n---\n"
        fm, _ = v.parse_frontmatter(text)
        self.assertEqual(fm["url"], "https://example.com:8080")


class VaultSchemaTests(unittest.TestCase):
    def test_loads_schemas_from_json(self):
        schemas = v.load_vault_schemas()
        self.assertIn("decision", schemas)
        self.assertIn("session", schemas)
        self.assertIn("drift", schemas)

    def test_decision_requires_canonical_keys(self):
        required = v.VAULT_SCHEMAS["decision"]
        for key in ("id", "type", "tags", "date", "authors", "status"):
            self.assertIn(key, required, f"decision must require {key}")

    def test_drift_requires_component_and_commit(self):
        required = v.VAULT_SCHEMAS["drift"]
        self.assertIn("commit", required)
        self.assertIn("component", required)

    def test_index_only_requires_type(self):
        # `index` has no per-type allOf rule; just the base "type" requirement.
        self.assertEqual(v.VAULT_SCHEMAS["index"], {"type"})


class CoverageRegexTests(unittest.TestCase):
    """Regexes are brittle. Pin them with explicit test cases."""

    def _matches(self, key: str, text: str) -> bool:
        import re as _re

        return _re.search(v.COVERAGE_CANON[key][1], text, _re.IGNORECASE) is not None

    def test_line_matches_canonical_forms(self):
        self.assertTrue(self._matches("line", "95% line coverage"))
        self.assertTrue(self._matches("line", "line coverage: 95%"))
        self.assertTrue(self._matches("line", "minimum = \"0.95\".toBigDecimal()"))

    def test_branch_matches_canonical_forms(self):
        self.assertTrue(self._matches("branch", "90% branch coverage"))
        self.assertTrue(self._matches("branch", "branch coverage: 90"))
        self.assertTrue(self._matches("branch", "0.90"))

    def test_domain_matches_canonical_forms(self):
        self.assertTrue(self._matches("domain", "98% line on domain"))
        self.assertTrue(self._matches("domain", "98% domain"))
        self.assertTrue(self._matches("domain", "domain 0.98"))
        self.assertTrue(self._matches("domain", "domain 98%"))

    def test_mutation_matches_canonical_forms(self):
        self.assertTrue(self._matches("mutation", "85% mutation"))
        self.assertTrue(self._matches("mutation", "mutationThreshold.set(85)"))
        self.assertTrue(self._matches("mutation", "PIT >= 85"))

    def test_does_not_match_non_coverage_text(self):
        # Avoid false positives on incidental percentages
        self.assertFalse(self._matches("line", "the cake is 95% gone"))


class ReportTests(unittest.TestCase):
    def test_summary_when_empty(self):
        r = v.Report()
        self.assertEqual(r.summary(), "OK: 0 issues.")

    def test_summary_groups_by_kind(self):
        r = v.Report()
        tmp = Path("/tmp/dummy.md")
        r.add(tmp, 1, "skill-frontmatter", "missing name")
        r.add(tmp, 1, "skill-frontmatter", "missing description")
        r.add(tmp, 1, "coverage-drift", "missing 95")
        s = r.summary()
        self.assertIn("3 issues", s)
        self.assertIn("skill-frontmatter=2", s)
        self.assertIn("coverage-drift=1", s)


class IntegrationOnRepoTests(unittest.TestCase):
    """Runs the actual validators against the actual repo. If these fail, the
    framework state itself is broken — exactly what the validator is for."""

    def test_skill_frontmatter_is_clean(self):
        r = v.Report()
        v.validate_skill_frontmatter(r)
        kinds = {i.kind for i in r.issues}
        self.assertNotIn(
            "skill-frontmatter",
            kinds,
            f"skills with broken frontmatter: {[str(i) for i in r.issues]}",
        )

    def test_jabrena_refs_resolve(self):
        r = v.Report()
        v.validate_jabrena_refs(r)
        self.assertEqual(
            [str(i) for i in r.issues if i.kind == "jabrena-ref-broken"],
            [],
        )

    def test_vault_frontmatter_is_clean(self):
        r = v.Report()
        v.validate_vault_frontmatter(r)
        self.assertEqual(
            [str(i) for i in r.issues if i.kind == "vault-frontmatter"],
            [],
        )

    def test_vault_wikilinks_resolve(self):
        r = v.Report()
        v.validate_vault_wikilinks(r)
        self.assertEqual(
            [str(i) for i in r.issues if i.kind == "vault-wikilink"],
            [],
        )

    def test_coverage_thresholds_consistent(self):
        r = v.Report()
        v.validate_coverage_consistency(r)
        self.assertEqual(
            [str(i) for i in r.issues if i.kind == "coverage-drift"],
            [],
        )

    def test_no_secrets_in_repo(self):
        r = v.Report()
        v.validate_no_secrets(r)
        self.assertEqual([str(i) for i in r.issues if i.kind == "possible-secret"], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
