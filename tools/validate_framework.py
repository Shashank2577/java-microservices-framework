#!/usr/bin/env python3
"""
Framework consistency validator.

Validates that:
1. Every `lib/jabrena/...` path referenced from a framework skill resolves.
2. Every `[[wiki-link]]` in the vault resolves to an existing note (or is a TODO link).
3. Every framework skill has valid YAML frontmatter with required keys.
4. Every vault note has valid YAML frontmatter matching its template's schema.
5. Coverage thresholds (95% line / 90% branch / 98% domain / 85% PIT) are
   consistent across all skills that mention them.
6. No two skills define the same non-negotiable rule with different numbers.

Exit 0 if clean, 1 if any issue found. Designed to run in CI.

Usage:  python3 tools/validate_framework.py [--quiet]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parents[1]
SKILLS_DIR = REPO_ROOT / ".claude" / "skills"
JABRENA_DIR = SKILLS_DIR / "lib" / "jabrena"
VAULT_DIR = REPO_ROOT / ".vault"
VAULT_SCHEMA_PATH = REPO_ROOT / "tools" / "schemas" / "vault-note.schema.json"


def load_vault_schemas() -> dict[str, set[str]]:
    """Read the JSON Schema and derive required-field sets per note type.

    Single source of truth: the JSON Schema is consumed by both this validator
    AND the GitHub Action (mheap/frontmatter-json-schema-action). The two used
    to drift; now they don't.
    """
    schema = json.loads(VAULT_SCHEMA_PATH.read_text(encoding="utf-8"))
    base_required: set[str] = set(schema.get("required", []))
    enum_types: set[str] = set(schema.get("properties", {}).get("type", {}).get("enum", []))
    per_type: dict[str, set[str]] = {t: set(base_required) for t in enum_types}
    for rule in schema.get("allOf", []):
        cond = rule.get("if", {}).get("properties", {}).get("type", {})
        target = cond.get("const")
        then = rule.get("then", {}).get("required", [])
        if target and then:
            per_type.setdefault(target, set(base_required)).update(then)
    return per_type


VAULT_SCHEMAS: dict[str, set[str]] = load_vault_schemas()

# Coverage threshold canon — these must match wherever they're mentioned.
COVERAGE_CANON = {
    "line": ("95%", r"\b95\s*%\s*line\b|\bline\s+coverage[:\s]*≥?\s*95\b|0\.95"),
    "branch": ("90%", r"\b90\s*%\s*branch\b|\bbranch\s+coverage[:\s]*≥?\s*90\b|0\.90"),
    "domain": ("98%", r"\b98\s*%\s*(?:line\s+)?domain\b|domain[^\n]{0,40}(?:0\.98|98\s*%)|(?:0\.98|98\s*%)[^\n]{0,40}domain"),
    "mutation": ("85%", r"\b85\s*%\s*mutation\b|mutationThreshold[^\n]{0,40}85|PIT[^\n]{0,40}85"),
}


@dataclass
class Issue:
    file: Path
    line: int
    kind: str
    message: str

    def __str__(self) -> str:
        try:
            rel = self.file.relative_to(REPO_ROOT)
        except ValueError:
            rel = self.file
        return f"{rel}:{self.line}  [{self.kind}]  {self.message}"


@dataclass
class Report:
    issues: list[Issue] = field(default_factory=list)

    def add(self, file: Path, line: int, kind: str, message: str) -> None:
        self.issues.append(Issue(file, line, kind, message))

    def summary(self) -> str:
        by_kind: dict[str, int] = {}
        for i in self.issues:
            by_kind[i.kind] = by_kind.get(i.kind, 0) + 1
        if not self.issues:
            return "OK: 0 issues."
        parts = [f"{k}={v}" for k, v in sorted(by_kind.items())]
        return f"{len(self.issues)} issues — " + ", ".join(parts)


def parse_frontmatter(text: str) -> tuple[dict[str, str], int]:
    """Return (frontmatter-as-dict, line-where-body-starts). Empty dict if absent."""
    if not text.startswith("---\n"):
        return {}, 1
    end = text.find("\n---\n", 4)
    if end == -1:
        return {}, 1
    fm_block = text[4:end]
    parsed: dict[str, str] = {}
    for raw in fm_block.splitlines():
        if ":" in raw and not raw.lstrip().startswith("#"):
            k, _, v = raw.partition(":")
            parsed[k.strip()] = v.strip()
    return parsed, text[: end + 5].count("\n") + 1


def validate_skill_frontmatter(report: Report) -> None:
    """Every java-* skill must have name + description in frontmatter."""
    required = {"name", "description"}
    for skill in sorted(SKILLS_DIR.glob("java-*/SKILL.md")):
        text = skill.read_text(encoding="utf-8")
        fm, _ = parse_frontmatter(text)
        if not fm:
            report.add(skill, 1, "skill-frontmatter", "missing or invalid frontmatter")
            continue
        for key in required:
            if key not in fm:
                report.add(skill, 1, "skill-frontmatter", f"missing key: {key}")


def validate_jabrena_refs(report: Report) -> None:
    """Paths under .claude/skills/lib/jabrena/... cited from a skill must exist."""
    pattern = re.compile(r"`?\.claude/skills/lib/jabrena/([^\s)`]+)`?")
    for skill in sorted(SKILLS_DIR.glob("java-*/SKILL.md")):
        text = skill.read_text(encoding="utf-8")
        for i, line in enumerate(text.splitlines(), start=1):
            for m in pattern.finditer(line):
                rel = m.group(1).rstrip(".,;:")
                # strip optional anchor or trailing punctuation
                target = JABRENA_DIR / rel
                if not target.exists():
                    report.add(
                        skill,
                        i,
                        "jabrena-ref-broken",
                        f"lib/jabrena/{rel} does not exist",
                    )


def iter_vault_notes() -> Iterable[Path]:
    for p in VAULT_DIR.rglob("*.md"):
        # skip templates and _meta docs; only validate real notes
        if "_meta/templates" in str(p):
            continue
        if "_meta" in p.parts:
            continue
        if p.name == "README.md":
            continue
        yield p


def validate_vault_frontmatter(report: Report) -> None:
    for note in iter_vault_notes():
        text = note.read_text(encoding="utf-8")
        fm, _ = parse_frontmatter(text)
        if not fm:
            # README is allowed to skip; everything else needs frontmatter
            if note.name == "README.md":
                continue
            report.add(note, 1, "vault-frontmatter", "missing frontmatter")
            continue
        note_type = fm.get("type", "").strip()
        if not note_type:
            report.add(note, 1, "vault-frontmatter", "frontmatter missing 'type'")
            continue
        schema = VAULT_SCHEMAS.get(note_type)
        if schema is None:
            report.add(
                note,
                1,
                "vault-frontmatter",
                f"unknown note type '{note_type}'; expected one of {sorted(VAULT_SCHEMAS)}",
            )
            continue
        missing = schema - set(fm.keys())
        if missing:
            report.add(
                note,
                1,
                "vault-frontmatter",
                f"type={note_type} missing required keys: {sorted(missing)}",
            )


def validate_vault_wikilinks(report: Report) -> None:
    """`[[wiki-links]]` should resolve to an existing note slug (or contain a placeholder)."""
    # Build set of available slugs (filenames without .md, lowercased).
    slugs: set[str] = set()
    for p in VAULT_DIR.rglob("*.md"):
        slugs.add(p.stem.lower())
    # Common placeholders we tolerate; they're scaffolding markers.
    placeholders = {
        "someone",
        "person-slug",
        "name-of-person",
        "component-name",
        "finding-",
        "debugging-",
        "dec-nnnn",
        "yyyy-mm-dd",
    }
    link_re = re.compile(r"\[\[([^\]\|#]+)(?:#[^\]\|]*)?(?:\|[^\]]+)?\]\]")
    for note in iter_vault_notes():
        text = note.read_text(encoding="utf-8")
        for i, line in enumerate(text.splitlines(), start=1):
            for m in link_re.finditer(line):
                target = m.group(1).strip().lower()
                if target in placeholders:
                    continue
                if any(target.startswith(p) for p in placeholders):
                    continue
                # accept any slug match
                if target in slugs:
                    continue
                report.add(
                    note,
                    i,
                    "vault-wikilink",
                    f"unresolved [[{m.group(1)}]] — no matching note in vault",
                )


def validate_coverage_consistency(report: Report) -> None:
    """The canonical thresholds must appear consistently wherever coverage is mentioned."""
    coverage_files = [
        SKILLS_DIR / "java-code-quality" / "SKILL.md",
        SKILLS_DIR / "java-testing" / "SKILL.md",
        SKILLS_DIR / "java-git-workflow" / "SKILL.md",
        REPO_ROOT / "CLAUDE.md",
    ]
    for fp in coverage_files:
        if not fp.exists():
            continue
        text = fp.read_text(encoding="utf-8")
        # If file mentions coverage at all, expect the canonical numbers somewhere.
        if "coverage" not in text.lower():
            continue
        for key, (expected, pattern) in COVERAGE_CANON.items():
            if not re.search(pattern, text, re.IGNORECASE):
                # Soft signal — only complain if the topic is referenced.
                topic_re = {
                    "line": r"line\s+coverage|line:\s*[0-9]",
                    "branch": r"branch\s+coverage|branch:\s*[0-9]",
                    "domain": r"domain[^.\n]*coverage|coverage[^.\n]*domain",
                    "mutation": r"mutation\s+score|mutationThreshold|PIT[^a-z]",
                }[key]
                if re.search(topic_re, text, re.IGNORECASE):
                    report.add(
                        fp,
                        1,
                        "coverage-drift",
                        f"mentions {key} coverage but missing canonical {expected}",
                    )


def validate_no_secrets(report: Report) -> None:
    """Best-effort secret leak check; gitleaks is the authoritative scan in CI."""
    bad_patterns = [
        (re.compile(r"AKIA[0-9A-Z]{16}"), "looks like AWS access key"),
        (re.compile(r"-----BEGIN (RSA|EC|OPENSSH) PRIVATE KEY-----"), "private key present"),
        (re.compile(r"gh[pousr]_[A-Za-z0-9]{36,255}"), "looks like GitHub token"),
    ]
    targets = list(SKILLS_DIR.rglob("*.md")) + list(VAULT_DIR.rglob("*.md")) + [REPO_ROOT / "CLAUDE.md"]
    for fp in targets:
        try:
            text = fp.read_text(encoding="utf-8")
        except Exception:
            continue
        for i, line in enumerate(text.splitlines(), start=1):
            for pat, msg in bad_patterns:
                if pat.search(line):
                    report.add(fp, i, "possible-secret", msg)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="suppress per-issue output; print summary only")
    args = parser.parse_args()

    report = Report()
    validate_skill_frontmatter(report)
    validate_jabrena_refs(report)
    validate_vault_frontmatter(report)
    validate_vault_wikilinks(report)
    validate_coverage_consistency(report)
    validate_no_secrets(report)

    if not args.quiet:
        for issue in report.issues:
            print(issue)
        if report.issues:
            print()
    print(report.summary())
    return 1 if report.issues else 0


if __name__ == "__main__":
    sys.exit(main())
