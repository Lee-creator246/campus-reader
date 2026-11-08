"""Anonymous direct-network probe from the machine invoking this script.

No proxy, cookies, credentials, saved response bodies, or full redirect URLs.
This does not determine whether any student can authenticate in Safari.
"""
from datetime import datetime, timezone
from html.parser import HTMLParser
import http.client
import json
from urllib.parse import urljoin, urlsplit, parse_qs

ENTRY = "http://my.bupt.edu.cn/xs_index.jsp?urltype=tree.TreeTempUrl&wbtreeid=1541"
NOTICES = "http://my.bupt.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154"
UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 Version/17.4 Mobile/15E148 Safari/604.1"


def known_place(raw):
    url = urlsplit(raw)
    if url.username or url.password or url.fragment:
        return None
    if url.hostname == "my.bupt.edu.cn" and url.scheme in ("http", "https"):
        if url.port in (None, 80 if url.scheme == "http" else 443):
            return "portal"
    if url.hostname == "auth.bupt.edu.cn" and url.scheme == "https" and url.port in (None, 443):
        return "cas"
    return None


class NoticeList(HTMLParser):
    def __init__(self, base):
        super().__init__()
        self.base = base
        self.links = set()
        self.has_password = False

    def handle_starttag(self, tag, attrs):
        attr = dict(attrs)
        if tag == "input" and attr.get("type", "").lower() == "password":
            self.has_password = True
        if tag != "a" or not attr.get("href"):
            return
        target = urljoin(self.base, attr["href"])
        try:
            url = urlsplit(target)
            query = parse_qs(url.query)
            if (known_place(target) == "portal" and url.path.endswith("/xntz_content.jsp")
                    and any(value.isdigit() for value in query.get("wbnewsid", []))):
                self.links.add(target)
        except ValueError:
            pass


def probe(target):
    url = target
    result = {"initialPath": urlsplit(url).path, "hops": []}
    try:
        for _ in range(5):
            host_type = known_place(url)
            if not host_type:
                return {**result, "result": "redirect_outside_portal"}
            parts = urlsplit(url)
            cls = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
            conn = cls(parts.hostname, parts.port, timeout=6)
            try:
                request_path = parts.path + ("?" + parts.query if parts.query else "")
                conn.request("GET", request_path, headers={"User-Agent": UA, "Accept": "text/html", "Accept-Encoding": "identity"})
                response = conn.getresponse()
                result["hops"].append({"host": parts.hostname, "status": response.status})
                if response.status in (301, 302, 303, 307, 308):
                    location = response.getheader("Location")
                    if not location:
                        return {**result, "result": "invalid_redirect"}
                    url = urljoin(url, location)
                    if known_place(url) == "cas":
                        return {**result, "result": "authentication_required"}
                    continue
                if response.status in (401, 403):
                    return {**result, "result": "access_denied"}
                if not 200 <= response.status < 300:
                    return {**result, "result": "http_error"}
                body = response.read(512 * 1024 + 1)
                if len(body) > 512 * 1024:
                    return {**result, "result": "response_too_large"}
                parser = NoticeList(url)
                parser.feed(body.decode("utf8", "replace"))
                if host_type == "cas" or parser.has_password or parts.path == "/system/resource/code/auth/clogin.jsp":
                    return {**result, "result": "authentication_required", "evidence": "campus_login_endpoint_or_form"}
                if urlsplit(target).path == "/list.jsp" and parser.links:
                    return {**result, "result": "anonymous_notice_links", "numberOfLinks": len(parser.links)}
                return {**result, "result": "reachable_unverified_page"}
            finally:
                conn.close()
        return {**result, "result": "too_many_redirects"}
    except (OSError, ValueError, http.client.HTTPException) as exc:
        return {**result, "result": "connection_failed", "errorType": type(exc).__name__}


if __name__ == "__main__":
    print(json.dumps({"testedAtUTC": datetime.now(timezone.utc).isoformat(), "scope": "current_machine_direct_anonymous",
                      "probes": [probe(ENTRY), probe(NOTICES)]}, indent=2))
