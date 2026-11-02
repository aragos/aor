"""Regressions for checking separately served production map assets."""
import gzip
import importlib.util
import io
import json
from email.message import Message
from pathlib import Path
import unittest
from unittest.mock import patch
from urllib.request import Request


spec = importlib.util.spec_from_file_location(
    "check_container", Path(__file__).with_name("check-container.py"))
check_container = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_container)


class AssetResponse(io.BytesIO):
    def __init__(self, body, headers):
        super().__init__(body)
        self.headers = Message()
        for key, value in headers.items():
            self.headers[key] = value


class BuiltClientTest(unittest.TestCase):
    origin = "http://127.0.0.1:18080"
    drawing = "/assets/boardGeometry-CBVWEg-N.json"
    headers = {
        "Content-Type": "application/json",
        "Cache-Control": "public, max-age=31536000, immutable",
        "Content-Encoding": "gzip",
    }
    geometry = {
        "bounds": [0, 0, 100, 100], "frame": {}, "provinces": {"acre": {}},
        "coasts": [], "areas": [], "seas": [], "terrain": [], "supports": [],
    }

    def verify(self, javascript, headers=None, geometry=None):
        def serve(request, timeout):
            url = request.full_url if isinstance(request, Request) else request
            if url == self.origin:
                return AssetResponse(b'<script type="module" src="/assets/index-HASH.js"></script>', {})
            if url == self.origin + "/assets/index-HASH.js":
                return AssetResponse(javascript.encode(), {})
            self.assertEqual(url, self.origin + self.drawing)
            self.assertIsInstance(request, Request)
            self.assertEqual(request.get_header("Accept-encoding"), "gzip")
            body = json.dumps(self.geometry if geometry is None else geometry).encode()
            return AssetResponse(gzip.compress(body), self.headers if headers is None else headers)

        with patch.object(check_container, "urlopen", side_effect=serve):
            return check_container.verify_built_client(self.origin)

    def test_vite_7_quoted_and_vite_8_backtick_urls_fetch_compressed_geometry(self):
        for quote in ['"', "'", "`"]:
            with self.subTest(quote=quote):
                javascript = f"var drawing={quote}{self.drawing}{quote};fetch(drawing)"
                self.assertEqual(self.verify(javascript), 1)

    def test_dynamic_templates_do_not_count_as_static_asset_urls(self):
        with self.assertRaisesRegex(RuntimeError, "does not reference separately loaded"):
            self.verify("var drawing=`/assets/boardGeometry-${hash}.json`")

    def test_unmatched_string_delimiters_do_not_count_as_asset_urls(self):
        with self.assertRaisesRegex(RuntimeError, "does not reference separately loaded"):
            self.verify(f"var drawing=`{self.drawing}\"")

    def test_backtick_urls_still_require_json_immutable_caching_and_gzip(self):
        for key, value, diagnostic in [
            ("Content-Type", "text/html", "application/json"),
            ("Cache-Control", "public, max-age=60", "immutable caching"),
            ("Content-Encoding", "identity", "gzip compression"),
        ]:
            with self.subTest(header=key):
                with self.assertRaisesRegex(RuntimeError, diagnostic):
                    self.verify(f"var drawing=`{self.drawing}`", {**self.headers, key: value})

    def test_backtick_urls_still_require_usable_geometry(self):
        with self.assertRaisesRegex(RuntimeError, "does not contain usable geometry"):
            self.verify(f"var drawing=`{self.drawing}`", geometry={**self.geometry, "provinces": {}})


if __name__ == "__main__":
    unittest.main()
