#!/usr/bin/env python3
"""Regression test the in-place patch applied to the official yt-dlp zipapp."""
from __future__ import annotations

import io
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import patch_ytdlp_xhamster as patcher  # noqa: E402

UPSTREAM_XHAMSTER = '''import itertools
import re
import urllib.parse

from .common import InfoExtractor
from ..utils import (
    determine_ext,
    str_or_none,
    try_get,
    url_or_none,
)

class XHamsterIE(InfoExtractor):
    _VALID_HEX_RE = r'[0-9a-fA-F]{12,}'
    def _decipher_format_url(self, format_url, format_id):
        return format_url

    def _real_extract(self, url):
        video_id = 'video-id'
        display_id = 'video-title'
        webpage = '<title>Video title</title>'
        initials = {'videoModel': {'sources': {}}, 'xplayerSettings': {'sources': {}}}
        if initials:
            video = initials['videoModel']
            title = video['title']
            formats = []
            format_urls = set()
            sources = try_get(video, lambda x: x['sources'], dict) or {}
            for format_id, formats_dict in sources.items():
                if not isinstance(formats_dict, dict):
                    continue
                for quality, format_item in formats_dict.items():
                    format_url = format_item
                    format_url = url_or_none(format_url)
                    if not format_url or format_url in format_urls:
                        continue
                    format_urls.add(format_url)
            return {'id': video_id, 'title': title, 'formats': formats}
'''


def test_source_patch() -> None:
    patched, changed = patcher.patch_source(UPSTREAM_XHAMSTER)
    assert changed
    compile(patched, patcher.MODULE_PATH, "exec")
    assert "import html\n" in patched
    assert "video = initials.get('videoModel') if isinstance(initials, dict) else None" in patched
    assert "str_or_none(video.get('title'))" in patched
    assert "self._og_search_title(webpage, default=None) or display_id or video_id" in patched
    assert "# VB_XHAMSTER_TITLE_GUARD" in patched
    assert "# VB_XHAMSTER_SOURCE_DECIPHER" in patched
    assert "self._decipher_format_url(format_url, f'{format_id}-{quality}')" in patched
    assert "video = initials['videoModel']" not in patched
    assert "title = video['title']" not in patched
    second, second_changed = patcher.patch_source(patched)
    assert not second_changed and second == patched


def test_zipapp_patch() -> None:
    prefix = b"#!/usr/bin/env python3\n# generated test zipapp\n"
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("__main__.py", "print('fixture')\n")
        archive.writestr(patcher.MODULE_PATH, UPSTREAM_XHAMSTER)
    with tempfile.TemporaryDirectory() as tmp:
        target = Path(tmp) / "yt-dlp"
        target.write_bytes(prefix + output.getvalue())
        changed = patcher.patch_zipapp(target)
        assert changed
        after = target.read_bytes()
        assert after.startswith(prefix), "The executable shebang/prefix must be preserved"
        with zipfile.ZipFile(io.BytesIO(after), "r") as archive:
            patched = archive.read(patcher.MODULE_PATH).decode("utf-8")
            assert "# VB_XHAMSTER_TITLE_GUARD" in patched
            assert "# VB_XHAMSTER_SOURCE_DECIPHER" in patched
            assert archive.read("__main__.py") == b"print('fixture')\n"
        assert not patcher.patch_zipapp(target), "The patch must be idempotent"


def test_encrypted_url_gate_shape() -> None:
    patched, _ = patcher.patch_source(UPSTREAM_XHAMSTER)
    assert "not url_or_none(format_url) or has_encrypted_path" in patched
    assert "re.match(rf'^{self._VALID_HEX_RE}[/,]', path.lstrip('/'))" in patched
    # The new logic keeps ordinary direct URLs untouched, and passes opaque strings
    # or URL paths beginning with the site's hex ciphertext to the existing decoder.


if __name__ == "__main__":
    test_source_patch()
    test_zipapp_patch()
    test_encrypted_url_gate_shape()
    print("PASS: xHamster title fallback, encrypted videoModel source decoding, zipapp prefix preservation, and idempotent patching")
