import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

WEB = Path(__file__).resolve().parents[1]


def load_build(name, source):
    spec = importlib.util.spec_from_file_location(name, source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CloudflareBrowserApiTests(unittest.TestCase):
    def test_only_fixed_upstream_build_sets_browser_origin(self):
        build = load_build("static_build", WEB / "build.py")
        cloudflare = load_build("worker_build", WEB / "cloudflare" / "build.py")
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            tmp = Path(directory)
            build.build(tmp / "static")
            site = tmp / "static" / "site"
            self.assertNotIn("publicApiOrigin", json.loads((site / "version.json").read_text()))
            for name, upstream in [("direct", "https://VPS.example/"), ("relative", "")]:
                cloudflare.package(site, tmp / name, upstream)
                project = tmp / name / "worker"
                version = json.loads((project / "site/version.json").read_text())
                self.assertEqual(version["mode"], "server")
                self.assertTrue(version["api"])
                if name == "direct":
                    self.assertEqual(version["publicApiOrigin"], "https://vps.example")
                else:
                    self.assertNotIn("publicApiOrigin", version)
                self.assertEqual(json.loads((project / "wrangler.json").read_text())["vars"]["UPSTREAM_ORIGIN"],
                                 version.get("publicApiOrigin", ""))

    def test_rejects_inputs_that_cannot_be_used_as_a_fixed_browser_origin(self):
        cloudflare = load_build("worker_build", WEB / "cloudflare" / "build.py")
        for bad in ["https://vps.example:443", "https://user@vps.example", "https://vps.example/#token",
                    "https://vps.example/other", "https://vps.example?next=a", "http://vps.example"]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                cloudflare.checked_origin(bad)
