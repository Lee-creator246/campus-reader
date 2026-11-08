"""Review presence-only BUPT portal alerts using an isolated local Edge window."""
import argparse
import contextlib
import datetime as dt
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit

LIST = "http://my.bupt.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1154"
CALLBACK = "http://my.bupt.edu.cn:80/system/resource/code/auth/clogin.jsp?owner=1664271694"
LOGIN = "https://auth.bupt.edu.cn/authserver/login?" + urlencode({"service": CALLBACK})
TITLE = "校园门户有新通知"
BODY = "请在学校授权的网络环境中打开官方原文，按门户要求登录。"
ID = re.compile(r"[1-9][0-9]{0,18}")
REPO = Path(__file__).resolve().parents[2]

DOM = """() => {
  if (document.querySelector('input[type=password]')) throw Error('login page');
  const lists = document.querySelectorAll('.main ul.am-list');
  if (lists.length !== 1) throw Error('unrecognized list');
  const rows = lists[0].querySelectorAll('li.am-list-item-dated');
  if (rows.length < 1 || rows.length > 200) throw Error('unexpected number of rows');
  return Array.from(rows).filter(row => !row.id.startsWith('more_template_')).map(row => {
    const links = row.querySelectorAll('a.am-list-item-hd[href]');
    const dates = row.querySelectorAll('span.am-list-date');
    if (links.length !== 1 || dates.length !== 1) throw Error('unrecognized item');
    return {id:row.id, date:dates[0].textContent.trim(), href:links[0].getAttribute('href')};
  });
}"""


def checked_url(url):
    if not isinstance(url, str) or re.search(r"[\x00-\x20\\#]", url):
        raise ValueError("门户地址含异常字符")
    parsed = urlsplit(url)
    if (parsed.scheme not in ("http", "https") or parsed.hostname != "my.bupt.edu.cn"
            or parsed.username is not None or parsed.password is not None
            or parsed.port not in (None, 80 if parsed.scheme == "http" else 443)):
        raise ValueError("地址不在官方门户")
    pairs = parse_qsl(parsed.query, strict_parsing=True, keep_blank_values=True, max_num_fields=4)
    fields = dict(pairs)
    if len(fields) != len(pairs):
        raise ValueError("门户参数重复")
    return parsed, fields


def notice(numeric, date, href, base=LIST):
    if not isinstance(numeric, str) or not ID.fullmatch(numeric):
        raise ValueError("通知编号无效")
    if not isinstance(date, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", date):
        raise ValueError("通知日期格式不可识别")
    dt.date.fromisoformat(date)  # Rejects invalid calendar dates.
    page, _ = checked_url(base)
    absolute = urljoin(base, href)
    target, fields = checked_url(absolute)
    if (page.scheme != target.scheme or target.path != "/xntz_content.jsp"
            or set(fields) != {"urltype","wbtreeid","wbnewsid"} or fields['wbnewsid'] != numeric
            or fields['urltype'] != 'news.NewsContentUrl' or not ID.fullmatch(fields['wbtreeid'])):
        raise ValueError("原文地址与列表编号不匹配")
    url = f"{target.scheme}://my.bupt.edu.cn/xntz_content.jsp?" + urlencode({
        "urltype": "news.NewsContentUrl", "wbtreeid": fields["wbtreeid"], "wbnewsid": numeric})
    return {"id":"n:"+numeric, "publishedAt":date, "url":url, "title":TITLE, "body":BODY}


def read_page(page):
    url = page.url
    parsed, fields = checked_url(url)
    if parsed.path != "/list.jsp" or fields != {"urltype":"tree.TreeTempUrl","wbtreeid":"1154"}:
        raise ValueError("须先完成学校登录并到达 1154 移动通知列表")
    records = {}
    for row in page.evaluate(DOM):
        item = notice(row["id"], row["date"], row["href"], url)
        if row["id"] in records and records[row["id"]] != item:
            raise ValueError("重复编号的信息不一致")
        records[row["id"]] = item
    if not records:
        raise ValueError("列表为空或模板未通过检查")
    return records


def stored(path):
    if not path.exists():
        return {}
    if path.stat().st_size > 512 * 1024:
        raise ValueError("现有文件超过服务上限")
    feed = json.loads(path.read_text(encoding="utf8"))
    if not isinstance(feed, dict) or feed.get('visibility') != 'public' or not isinstance(feed.get('notices'), list):
        raise ValueError("现有文件不是公开 feed")
    records = {}
    for item in feed["notices"]:
        if not isinstance(item, dict) or not isinstance(item.get("id"), str) or not item["id"].startswith("n:"):
            raise ValueError("现有文件不是本工具的最小提醒 feed")
        numeric = item["id"][2:]
        normalized = notice(numeric, item.get("publishedAt"), item.get("url"), item.get("url"))
        if normalized != item or numeric in records:
            raise ValueError("现有文件含其他内容或重复编号，请单独人工管理")
        records[numeric] = normalized
    return records


@contextlib.contextmanager
def writer_lock(path):
    # OS advisory locks are released even when the browser/terminal exits abruptly.
    with path.open("a+b") as handle:
        if handle.tell() == 0:
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def export(path, candidates, selection):
    path = path.resolve()
    if path == REPO or REPO in path.parents:
        raise ValueError("请把输出放在活动仓库之外")
    if not selection or any(n not in candidates for n in selection):
        raise ValueError("只能选择当前页显示的编号")
    path.parent.mkdir(parents=True, exist_ok=True)
    with writer_lock(path.with_suffix(path.suffix + ".lock")):
        records = stored(path)
        records.update({n:candidates[n] for n in selection})
        if len(records) > 200:
            raise ValueError("超过 200 条，先人工整理旧提醒")
        rows = sorted(records.values(), key=lambda x:(x["publishedAt"], int(x["id"][2:])), reverse=True)
        payload = json.dumps({"visibility":"public","notices":rows}, ensure_ascii=False, indent=2) + "\n"
        if len(payload.encode("utf8")) > 512 * 1024:
            raise ValueError("超过服务的 512 KiB 上限")
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temp:
            tmp = Path(temp.name)
        try:
            tmp.write_text(payload, encoding="utf8")
            tmp.replace(path)
        finally:
            tmp.unlink(missing_ok=True)
        return len(rows)


def official_navigation(route):
    request = route.request
    if request.is_navigation_request():
        url = urlsplit(request.url)
        cas = url.scheme == "https" and url.hostname == "auth.bupt.edu.cn" and url.port in (None, 443)
        portal = (url.hostname == "my.bupt.edu.cn" and url.scheme in ("http","https")
                  and url.port in (None, 80 if url.scheme == "http" else 443))
        if not (cas or portal) or url.username or url.password or (request.method == "POST" and not cas):
            return route.abort()
    return route.continue_()


def collect(target):
    from playwright.sync_api import Error, sync_playwright
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=False)
        context = browser.new_context(accept_downloads=False, service_workers="block")
        try:
            context.route("**/*", official_navigation)
            page = context.new_page()
            page.goto(LOGIN, wait_until="domcontentloaded")
            print("在独立 Edge 窗口的学校 HTTPS 认证页面完成登录。此终端只接收操作和通知编号。")
            while True:
                command = input("登录后回车检查/刷新最新页，输入 q 退出：").strip()
                if command == "q":
                    return
                try:
                    response = page.goto(LIST, wait_until="domcontentloaded")
                    if response is None or response.status != 200:
                        raise ValueError("门户列表未成功返回")
                    candidates = read_page(page)
                except (Error, ValueError):
                    print("没有读到已认证的 1154 列表。检查校园通道、在窗口完成学校验证后重试。")
                    continue
                for numeric, item in candidates.items():
                    print(numeric, item["publishedAt"])
                ids = input("输入同意公开提醒的编号，以空格分隔。直接回车跳过：").split()
                if ids:
                    try:
                        count = export(target, candidates, ids)
                    except (OSError, ValueError, TypeError) as error:
                        print(f"未写入；请检查选择、输出文件和同时运行的实例（{type(error).__name__}）。")
                    else:
                        print(f"本地审核文件已写入 {target}，共 {count} 条，请复核后自行发布。")
        finally:
            context.close()
            browser.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    home = Path(os.getenv("LOCALAPPDATA") or Path.home()) / "CampusReaderOperator"
    parser.add_argument("--output", type=Path, default=home/"public-notices.json")
    try:
        collect(parser.parse_args().output)
    except (EOFError, KeyboardInterrupt):
        print("已结束本次操作。")
    except Exception as failure:
        parser.exit(1, f"采集未完成（{type(failure).__name__}），请检查独立环境、Edge 与校园网络。\n")
