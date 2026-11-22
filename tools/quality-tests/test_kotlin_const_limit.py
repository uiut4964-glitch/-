#!/usr/bin/env python3
"""Guard against JVM constant-pool UTF-8 strings exceeding the class-file limit."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
DECL = re.compile(
    r"(?:\b(?:private|internal|public|protected)\s+)?const\s+val\s+"
    r"[A-Za-z_][A-Za-z0-9_]*\s*=\s*\"\"\"(.*?)\"\"\"",
    re.DOTALL,
)
LIMIT = 65535
found = 0
errors = []
for source in (ROOT / "app/src/main/java").rglob("*.kt"):
    content = source.read_text(encoding="utf-8")
    for match in DECL.finditer(content):
        found += 1
        size = len(match.group(1).encode("utf-8"))
        if size > LIMIT:
            errors.append(f"{source.relative_to(ROOT)}: const raw string is {size} UTF-8 bytes (limit {LIMIT})")

if errors:
    raise SystemExit("\n".join(errors))
print(f"Kotlin constant-string limit passed: checked {found} raw const strings; none exceeds {LIMIT} UTF-8 bytes")
