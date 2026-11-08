"""Exercise the real DOM extraction on an isolated Edge page containing synthetic HTML."""
import json
from pathlib import Path
import tempfile
import unittest
from playwright.sync_api import sync_playwright, Error
from collect import LIST, TITLE, BODY, export, read_page


def row(n, date, href=None):
    link = href or f"/xntz_content.jsp?urltype=news.NewsContentUrl&wbtreeid=1154&wbnewsid={n}"
    return (f'<li class="am-list-item-dated" id="{n}"><a class="am-list-item-hd" href="{link}">'
            f'PRIVATE-TITLE-{n}</a><span class="am-list-date">{date}</span></li>')


class OperatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(channel="msedge", headless=True)

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def read(self, rows, url=LIST, prefix=""):
        with self.browser.new_context() as context:
            page = context.new_page()
            data = f'{prefix}<main class="main"><ul class="am-list">{rows}</ul></main>'
            page.route("**/*", lambda r:r.fulfill(status=200, content_type="text/html; charset=utf-8", body=data))
            page.goto(url)
            return read_page(page)

    def test_explicit_selection_atomic_merge_and_no_private_text(self):
        candidates = self.read(row("11","2026-11-01") + row("12","2026-11-03") + row("13","2026-11-02"))
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)/"public-notices.json"
            with self.assertRaises(ValueError):
                export(dest,candidates,[])
            self.assertFalse(dest.exists())
            self.assertEqual(export(dest,candidates,["11"]),1)
            self.assertEqual(export(dest,candidates,["12","12"]),2)
            before = dest.read_bytes()
            with self.assertRaises(ValueError):
                export(dest,candidates,["999"])
            self.assertEqual(dest.read_bytes(),before)
            self.assertNotIn(b"PRIVATE-TITLE",before)
            items = json.loads(before)["notices"]
            self.assertEqual([item["id"] for item in items],["n:12","n:11"])
            for item in items:
                self.assertEqual((item["title"],item["body"]),(TITLE,BODY))
                self.assertEqual(set(item),{'id','title','body','url','publishedAt'})

    def test_template_origin_parameter_and_date_rejections(self):
        valid = row("11","2026-11-01")
        invalid = [
            (valid,LIST,"<input type=password>"),
            (valid,LIST.replace("1154","1541"),""),
            (row("11","2026-02-30"),LIST,""),
            (row("11","2026-11-01","https://invalid.example/xntz_content.jsp?wbnewsid=11"),LIST,""),
            (valid.replace("wbnewsid=11","wbnewsid=12"),LIST,""),
            (valid.replace("wbnewsid=11","wbnewsid=11&ticket=private"),LIST,""),
            (valid.replace("wbnewsid=11","wbnewsid=11&wbnewsid=11"),LIST,""),
        ]
        for rows,url,prefix in invalid:
            with self.subTest(url=url), self.assertRaises((ValueError,Error)):
                self.read(rows,url,prefix)


if __name__ == "__main__":
    unittest.main()
