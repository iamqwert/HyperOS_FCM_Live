#!/usr/bin/env python3
"""Extract and normalize a version section from CHANGELOG.md for GitHub Releases.

Rules:
1. Extract the section starting at ``## <versionName>`` (up to the next ``## ``).
2. Ensure a horizontal rule (``---``) sits directly beneath ``### 简体中文`` and
   ``### English`` without duplicating an existing divider.
3. Normalize bullet points outside fenced code blocks:
   - Convert ``* `` / ``+ `` / ``• `` / ``· `` (and ``•``/``·`` without space) to
     standard ``- `` while preserving leading indentation (e.g. ``  - ``).
   - Auto-prefix unindented plain text lines with ``- ``, while keeping headings,
     dividers, ordered lists, blockquotes, tables, HTML lines, and indented
     continuation lines untouched.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

HR_RE = re.compile(r"^\s*(?:-{3,}|\*{3,}|_{3,})\s*$")
LANG_HEADING_RE = re.compile(r"^###\s+(简体中文|English)\s*$")
HEADING_RE = re.compile(r"^\s*#{1,6}\s+")
ORDERED_LIST_RE = re.compile(r"^\s*\d+[.)]\s+")
DASH_BULLET_RE = re.compile(r"^(\s*)-\s+(.*)$")
ALT_BULLET_RE = re.compile(r"^(\s*)[*+]\s+(.*)$")
DOT_BULLET_RE = re.compile(r"^(\s*)[•·‣⁃]\s*(.+)$")
SPECIAL_LINE_RE = re.compile(r"^\s*(?:>|\||<)")
FENCE_RE = re.compile(r"^\s*(`{3,}|~{3,})")


def extract_section(text: str, version_name: str) -> list[str]:
    header_re = re.compile(rf"^## {re.escape(version_name)}(?: |$)")
    lines: list[str] = []
    grab = False
    for line in text.splitlines():
        if not grab:
            if header_re.match(line):
                grab = True
            continue
        if line.startswith("## "):
            break
        lines.append(line)
    return lines


def normalize_bullet(line: str) -> str:
    if not line.strip():
        return ""
    if (
        HEADING_RE.match(line)
        or HR_RE.match(line)
        or ORDERED_LIST_RE.match(line)
        or DASH_BULLET_RE.match(line)
        or SPECIAL_LINE_RE.match(line)
    ):
        return line
    match = ALT_BULLET_RE.match(line)
    if match:
        return f"{match.group(1)}- {match.group(2)}"
    match = DOT_BULLET_RE.match(line)
    if match:
        return f"{match.group(1)}- {match.group(2)}"
    # Preserve indented continuation lines; auto-bullet top-level plain lines.
    if line[0].isspace():
        return line
    return f"- {line}"


def format_release_notes(lines: list[str]) -> str:
    out: list[str] = []
    in_fence = False
    fence_char = ""
    pending_hr = False

    for raw in lines:
        line = raw.rstrip()

        fence_match = FENCE_RE.match(line)
        if fence_match:
            marker = fence_match.group(1)
            if not in_fence:
                in_fence = True
                fence_char = marker[0]
            elif marker[0] == fence_char:
                in_fence = False
                fence_char = ""
            if pending_hr:
                out.extend(["", "---", ""])
                pending_hr = False
            out.append(line)
            continue

        if in_fence:
            out.append(line)
            continue

        if pending_hr:
            if not line.strip():
                continue
            if HR_RE.match(line):
                out.extend(["", line])
                pending_hr = False
                continue
            out.extend(["", "---", ""])
            pending_hr = False

        if LANG_HEADING_RE.match(line):
            out.append(line)
            pending_hr = True
            continue

        out.append(normalize_bullet(line))

    if pending_hr:
        out.extend(["", "---"])

    # Trim leading/trailing blank lines.
    while out and not out[0].strip():
        out.pop(0)
    while out and not out[-1].strip():
        out.pop()
    return "\n".join(out) + ("\n" if out else "")


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: compose_release_notes.py <version_name> [changelog_path]", file=sys.stderr)
        return 2
    version_name = sys.argv[1]
    changelog_path = Path(sys.argv[2]) if len(sys.argv) >= 3 else Path("CHANGELOG.md")
    text = changelog_path.read_text(encoding="utf-8")
    section = extract_section(text, version_name)
    sys.stdout.write(format_release_notes(section))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
