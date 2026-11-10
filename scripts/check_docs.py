#!/usr/bin/env python3
"""Validate the tracked Markdown before a release is built.

Three checks, all read-only and stdlib-only so CI needs no extra setup:

1. Every local Markdown link resolves to a *tracked* file or directory. Using
   the index rather than the working tree is deliberate: internal notes that
   are not in the repository must not be linked from a shipped document, or
   the link works locally and 404s for everyone else.
2. Every ``§N.N`` reference has a matching numbered heading. Headings in this
   repository are written ``## 1. title``, so the trailing dot is optional in
   the pattern -- missing it reports top-level sections as dangling.
3. User-facing documents must not advertise hooks that were retired in 4.4.0.

Run from anywhere: ``python3 scripts/check_docs.py``. Exit 1 on any finding.
"""

from __future__ import annotations

import posixpath
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]

LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
SECTION_REF_RE = re.compile(r"§\s*(\d+(?:\.\d+)*)")
HEADING_RE = re.compile(r"^#{2,4}\s+(\d+(?:\.\d+)*)\.?\s")
EXTERNAL_RE = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//|#)", re.IGNORECASE)

USER_DOCS = ("README.md", "README_EN.md", "HELP.md", "HELP_EN.md")

# Method / class names removed from the install surface. A document is allowed
# to discuss a removal (FCM_HOOK_TECH_DOC.md does), but must not present one as
# a live hook -- hence the check is limited to the user-facing documents.
RETIRED_HOOKS = {
    "isNeedCachedBroadcast": "4.4.0 removed the broadcast-cache gate (S11)",
    "deferBroadcast": "S3/S6 removed 2026-10-30; covers deferBroadcastForMiui too",
    "isNeedCachedAlarmForAurogon": "S31, removed 2026-10-30",
    "GmsObserver#disableGmsApps": "no such method on OS3/OS4 dex",
    "setGmsDnsBlockerState": "NetdExecutor, no caller on OS4",
    "triggerGMSLimitAction": "no automatic trigger surface on OS3/OS4",
    "UserConfigureHelper": "arg-force hook removed as structurally dead",
    "TrafficProbe": "removed in 4.4.0",
    "WakePathProbe": "removed in 4.4.0",
    "UnsafeUtils": "removed in 4.4.0",
}


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True
    )
    return result.stdout


def tracked_files() -> set[str]:
    return {line.strip() for line in git("ls-files").splitlines() if line.strip()}


def markdown_files(index: set[str]) -> list[str]:
    return sorted(p for p in index if p.lower().endswith(".md"))


def link_target(raw: str) -> str | None:
    target = raw.strip()
    if target.startswith("<") and ">" in target:
        target = target[1:target.index(">")]
    else:
        # A title may follow the path after whitespace: [x](file "Title").
        target = target.split()[0] if target.split() else ""
    if not target or EXTERNAL_RE.match(target):
        return None
    target = unquote(target.split("#", 1)[0])
    return target or None


def check_links(index: set[str], docs: list[str]) -> list[str]:
    findings: list[str] = []
    for doc in docs:
        base = posixpath.dirname(doc)
        text = (ROOT / doc).read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            for raw in LINK_RE.findall(line):
                target = link_target(raw)
                if target is None:
                    continue
                resolved = posixpath.normpath(posixpath.join(base, target))
                if resolved.startswith("../") or resolved == "..":
                    findings.append(f"{doc}:{lineno}: link escapes repository: {raw}")
                elif resolved in index or any(
                    p.startswith(resolved + "/") for p in index
                ):
                    continue
                else:
                    findings.append(f"{doc}:{lineno}: untracked/missing target: {raw}")
    return findings


def numbered_headings(docs: list[str]) -> set[str]:
    headings: set[str] = set()
    for doc in docs:
        text = (ROOT / doc).read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            match = HEADING_RE.match(line)
            if match:
                headings.add(match.group(1))
    return headings


def check_sections(index: set[str], docs: list[str]) -> list[str]:
    headings = numbered_headings(docs)
    checked = [d for d in docs if d in USER_DOCS or d == "FCM_HOOK_TECH_DOC.md"]
    findings: list[str] = []
    for doc in checked:
        text = (ROOT / doc).read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            for number in SECTION_REF_RE.findall(line):
                if number not in headings:
                    findings.append(f"{doc}:{lineno}: dangling section §{number}")
    return findings


def check_retired_hooks(docs: list[str]) -> list[str]:
    findings: list[str] = []
    for doc in docs:
        if doc not in USER_DOCS:
            continue
        text = (ROOT / doc).read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            for name, why in RETIRED_HOOKS.items():
                if name in line:
                    findings.append(
                        f"{doc}:{lineno}: retired hook advertised ({why}): {name}"
                    )
    return findings


def main() -> int:
    index = tracked_files()
    docs = markdown_files(index)
    findings = (
        check_links(index, docs)
        + check_sections(index, docs)
        + check_retired_hooks(docs)
    )
    if findings:
        print(f"doc check: {len(findings)} finding(s) across {len(docs)} tracked docs")
        for finding in findings:
            print(f"  - {finding}")
        return 1
    print(f"doc check: OK ({len(docs)} tracked docs, links and sections resolve)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
