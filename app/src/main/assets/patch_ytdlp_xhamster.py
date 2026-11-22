#!/usr/bin/env python3
"""Patch the bundled yt-dlp zipapp for xHamster's changed hydration payloads.

This is intentionally narrow and fail-closed: it modifies only yt_dlp/extractor/xhamster.py
and aborts if the upstream source anchor is unknown. The original official artifact is
SHA-256 verified by prepare_android_runtime.sh before this patch is applied.
"""
from __future__ import annotations

import copy
import io
import os
import re
import stat
import sys
import tempfile
import zipfile
from pathlib import Path

PATCH_MARKERS = (
    "# VB_XHAMSTER_TITLE_GUARD",
    "# VB_XHAMSTER_SOURCE_DECIPHER",
)
MODULE_PATH = "yt_dlp/extractor/xhamster.py"


def patch_source(source: str) -> tuple[str, bool]:
    if all(marker in source for marker in PATCH_MARKERS):
        return source, False

    # html.unescape also fixes xHamster's HTML-encoded hydrated titles.
    if "import html\n" not in source:
        if "import itertools\n" not in source:
            raise RuntimeError("Unsupported yt-dlp xHamster module: import anchor not found")
        source = source.replace("import itertools\n", "import html\nimport itertools\n", 1)

    title_pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)video\s*=\s*initials\[['\"]videoModel['\"]\][ \t]*\r?\n"
        r"(?P=indent)title\s*=\s*video\[['\"]title['\"]\][ \t]*$"
    )
    title_replacement = (
        "@INDENT@video = initials.get('videoModel') if isinstance(initials, dict) else None\n"
        "@INDENT@if not isinstance(video, dict):\n"
        "@INDENT@    video = {}\n"
        "@INDENT@raw_title = str_or_none(video.get('title'))\n"
        "@INDENT@title = html.unescape(raw_title) if raw_title else self._og_search_title(webpage, default=None) or display_id or video_id\n"
        "@INDENT@# VB_XHAMSTER_TITLE_GUARD"
    )
    source, title_count = title_pattern.subn(
        lambda m: title_replacement.replace("@INDENT@", m.group("indent")), source, count=1
    )
    if title_count != 1:
        raise RuntimeError("Unsupported yt-dlp xHamster module: videoModel/title anchor not found")

    # Newer xHamster pages may store hex ciphertexts in videoModel.sources.
    # The official extractor already has _decipher_format_url for these values,
    # but historically applied it only to xplayerSettings.sources.
    source_pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)format_url\s*=\s*format_item\s*\r?\n"
        r"(?P=indent)format_url\s*=\s*url_or_none\(format_url\)\s*$"
    )
    source_replacement = (
        "@INDENT@if isinstance(format_item, dict):\n"
        "@INDENT@    raw_format_url = format_item.get('url') or format_item.get('fallback')\n"
        "@INDENT@else:\n"
        "@INDENT@    raw_format_url = format_item\n"
        "@INDENT@format_url = str_or_none(raw_format_url)\n"
        "@INDENT@if format_url:\n"
        "@INDENT@    path = urllib.parse.urlparse(format_url).path if url_or_none(format_url) else ''\n"
        "@INDENT@    has_encrypted_path = bool(re.match(rf'^{self._VALID_HEX_RE}[/,]', path.lstrip('/')))\n"
        "@INDENT@    if not url_or_none(format_url) or has_encrypted_path:\n"
        "@INDENT@        format_url = self._decipher_format_url(format_url, f'{format_id}-{quality}')\n"
        "@INDENT@format_url = url_or_none(format_url)\n"
        "@INDENT@# VB_XHAMSTER_SOURCE_DECIPHER"
    )
    source, source_count = source_pattern.subn(
        lambda m: source_replacement.replace("@INDENT@", m.group("indent")), source, count=1
    )
    if source_count != 1:
        raise RuntimeError("Unsupported yt-dlp xHamster module: source-loop anchor not found")

    # Validate syntax before writing the executable archive.
    compile(source, MODULE_PATH, "exec")
    return source, True


def patch_zipapp(path: Path) -> bool:
    original = path.read_bytes()
    mode = stat.S_IMODE(path.stat().st_mode)
    try:
        source_zip = zipfile.ZipFile(io.BytesIO(original), "r")
    except zipfile.BadZipFile as exc:
        raise RuntimeError("yt-dlp executable is not a readable zipapp") from exc

    with source_zip:
        try:
            raw_source = source_zip.read(MODULE_PATH)
        except KeyError as exc:
            raise RuntimeError(f"yt-dlp zipapp is missing {MODULE_PATH}") from exc
        decoded = raw_source.decode("utf-8")
        patched_source, changed = patch_source(decoded)
        if not changed:
            return False

        zip_start = original.find(b"PK\x03\x04")
        if zip_start < 0:
            raise RuntimeError("Could not locate zipapp payload after its executable prefix")
        executable_prefix = original[:zip_start]

        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", allowZip64=True) as destination:
            destination.comment = source_zip.comment
            for info in source_zip.infolist():
                cloned = copy.copy(info)
                contents = patched_source.encode("utf-8") if info.filename == MODULE_PATH else source_zip.read(info)
                destination.writestr(cloned, contents)

        updated = executable_prefix + output.getvalue()

    # Verify the rewritten self-extracting ZIP before replacing the original.
    with zipfile.ZipFile(io.BytesIO(updated), "r") as verify:
        check = verify.read(MODULE_PATH).decode("utf-8")
        if not all(marker in check for marker in PATCH_MARKERS):
            raise RuntimeError("Patched xHamster module failed post-write verification")
        if "title = video['title']" in check:
            raise RuntimeError("Unprotected xHamster title lookup remains after patch")
        if "self._decipher_format_url(format_url, f'{format_id}-{quality}')" not in check:
            raise RuntimeError("Encrypted videoModel source decoder did not survive packaging")

    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(updated)
            out.flush()
            os.fsync(out.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return True


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch_ytdlp_xhamster.py PATH_TO_YT_DLP_ZIPAPP", file=sys.stderr)
        return 2
    target = Path(sys.argv[1])
    if not target.is_file():
        print("yt-dlp zipapp not found", file=sys.stderr)
        return 2
    try:
        changed = patch_zipapp(target)
    except Exception as exc:
        print(f"xHamster yt-dlp patch failed: {exc}", file=sys.stderr)
        return 1
    print("xHamster title fallback and encrypted rendition decoding applied" if changed
          else "xHamster yt-dlp patch already present")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
