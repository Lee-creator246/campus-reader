import { Library, MAX_BYTES, SOURCE_NAMES, noticesFrom } from "./data.js";
import { setupPush } from "./push.js";
import { publicApiURL } from "./api-url.js";

const $ = id => document.getElementById(id);
const library = new Library();
const tabs = ["all", "unread", "later"];
const issuePage = "https://github.com/Lee-creator246/campus-reader/issues/new";
const scrollPositions = new Map();
const notices = {campus: "", local: "", demo: ""};
let build = {version: "0.9.1", commit: "", mode: "static"};
let currentDetail = "";
let requestedCampus = false;
let loadingCampus = false;

function status(element, message) {
  element.textContent = message;
  element.hidden = !message;
}
function serverMode() {
  return build.mode === "server" && build.api !== false;
}
function apiURL(operation) {
  return publicApiURL(build, operation);
}
function route() {
  const parts = location.hash.slice(1).split("/");
  let source = Object.hasOwn(SOURCE_NAMES, parts[0]) ? parts[0] : "demo";
  if (source === "campus" && !serverMode()) source = "demo";
  const tab = tabs.includes(parts[1]) ? parts[1] : "all";
  let id = "";
  try { id = decodeURIComponent(parts.slice(2).join("/")); } catch { /* Invalid deep link shows the list. */ }
  return {source, tab, id};
}
function path(source, tab = "all", id = "") {
  return "#" + source + "/" + tab + (id ? "/" + encodeURIComponent(id) : "");
}
function move(hash, fromList = false, replace = false) {
  if (fromList) scrollPositions.set(path(route().source, route().tab), window.scrollY);
  history[replace ? "replaceState" : "pushState"]({fromList}, "", hash);
  render();
}
function dateText(value) {
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", timeZone: "Asia/Shanghai",
  }).format(new Date(value));
}
function span(parent, cls, text) {
  const child = document.createElement("span");
  child.className = cls;
  child.textContent = text;
  parent.append(child);
}
function makeRow(item, source, tab) {
  const li = document.createElement("li");
  const anchor = document.createElement("a");
  anchor.className = "row";
  anchor.href = path(source, tab, item.id);
  anchor.dataset.noticeId = item.id;
  const read = library.has(source, item.id, "read");
  anchor.dataset.read = String(read);
  span(anchor, "row-title", item.title);
  const meta = document.createElement("div");
  meta.className = "row-meta";
  span(meta, "", dateText(item.publishedAt));
  span(meta, read ? "" : "unread", read ? "已读" : "未读");
  if (library.has(source, item.id, "later")) span(meta, "", "稍后读");
  anchor.append(meta);
  anchor.addEventListener("click", event => {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    move(anchor.hash, true);
  });
  li.append(anchor);
  return li;
}
function showList(source, tab) {
  const list = library.feeds[source];
  const visible = list.filter(item => tab === "all" || (tab === "unread" ?
    !library.has(source, item.id, "read") : library.has(source, item.id, "later")));
  $("count").textContent = visible.length + " 条";
  $("notices").replaceChildren(...visible.map(item => makeRow(item, source, tab)));
  for (const key of tabs) {
    if (key === tab) $("tab-" + key).setAttribute("aria-current", "page");
    else $("tab-" + key).removeAttribute("aria-current");
  }
  let message = "";
  if (visible.length === 0) {
    if (source === "local" && !list.length) message = "选择 JSON 文件以查看自己的通知。";
    else if (tab === "unread") message = "当前来源没有未读通知。";
    else if (tab === "later") message = "当前来源还没有加入稍后读的通知。";
    else if (source === "campus") message = "当前没有可显示的公开通知。";
  }
  status($("empty"), message);
}
function showDetail(source, item) {
  const key = source + ":" + item.id;
  const entering = currentDetail !== key;
  if (entering) library.flag(source, item.id, "read", true);
  currentDetail = key;
  $("detail-title").textContent = item.title;
  $("detail-source").textContent = SOURCE_NAMES[source];
  $("detail-date").textContent = dateText(item.publishedAt);
  $("detail-date").dateTime = item.publishedAt;
  $("detail-body").textContent = item.body || "此通知没有附带正文。";
  const saved = library.has(source, item.id, "later");
  $("detail-later").textContent = saved ? "移出稍后读" : "加入稍后读";
  $("detail-later").setAttribute("aria-pressed", String(saved));
  $("mark-unread").textContent = library.has(source, item.id, "read") ? "标记未读" : "标记已读";
  $("original").hidden = !item.url;
  if (item.url) $("original").href = item.url;
  else $("original").removeAttribute("href");
  if (entering) {
    $("detail-title").focus({preventScroll: true});
    $("detail-view").scrollIntoView({block: "start"});
  }
}
function render() {
  const {source, tab, id} = route();
  $("source").value = source;
  $("source-description").textContent = source === "demo" ? "合成通知，供体验阅读流程。" :
    source === "local" ? "仅保存在本浏览器中。导入新文件将替换这份通知列表。" :
    "仅显示经授权公开的通知。上次刷新：" + (library.updated ? new Date(library.updated).toLocaleString("zh-CN") : "尚未成功刷新");
  $("local-tools").hidden = source !== "local";
  $("clear-import").hidden = !library.feeds.local.length;
  $("refresh-campus").hidden = source !== "campus";
  $("refresh-campus").disabled = loadingCampus;
  const item = id ? library.feeds[source].find(n => n.id === id) : null;
  const missing = id && !item && !loadingCampus;
  status($("source-status"), missing ? "这条通知不在当前来源中，请从列表选择。" : notices[source]);
  $("list-view").hidden = Boolean(item);
  $("detail-view").hidden = !item;
  document.title = (item ? item.title + " · " : "") + "北邮人信息 · Safari 内测";
  if (item) showDetail(source, item);
  else {
    const leaving = Boolean(currentDetail);
    currentDetail = "";
    showList(source, tab);
    if (leaving) window.scrollTo(0, scrollPositions.get(path(source, tab)) || 0);
  }
  status($("storage-status"), library.notice);
  if (source === "campus" && !requestedCampus) refreshCampus();
}
async function request(url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 22000);
  try {
    const response = await fetch(url, {...options, credentials: "omit", signal: controller.signal, redirect: "error", cache: "no-store"});
    if (!response.ok) throw new Error("HTTP " + response.status);
    if (!response.headers.get("content-type")?.includes("application/json")) throw new Error("返回格式有误");
    const text = await response.text();
    if (new TextEncoder().encode(text).length > MAX_BYTES) throw new Error("返回内容超过 512 KiB");
    return JSON.parse(text);
  } finally { clearTimeout(timer); }
}
async function refreshCampus() {
  if (!serverMode() || loadingCampus) return;
  loadingCampus = requestedCampus = true;
  notices.campus = "正在刷新公开通知…";
  render();
  try {
    const payload = await request(apiURL("notices"));
    library.feeds.campus = noticesFrom(payload);
    library.updated = new Date().toISOString();
    library.save();
    notices.campus = "";
  } catch (error) {
    const cached = library.feeds.campus.length > 0 ? "继续显示本机缓存。" : "可切换到演示数据。";
    notices.campus = "暂时无法获取公开通知（" + error.message + "）。" + cached;
  } finally {
    loadingCampus = false;
    render();
  }
}
async function importFile(input) {
  const file = input.files[0];
  if (!file) return;
  try {
    if (file.size > MAX_BYTES) throw new Error("文件超过 512 KiB");
    const rows = noticesFrom(JSON.parse(await file.text()));
    library.feeds.local = rows;
    library.save();
    notices.local = "已导入 " + rows.length + " 条通知。";
    move(path("local"), false, true);
  } catch (error) {
    notices.local = "导入失败：" + (error instanceof SyntaxError ? "JSON 格式有误" : error.message);
    render();
  } finally { input.value = ""; }
}
function feedbackPayload() {
  return {message: $("feedback-message").value.trim(), version: build.version, commit: build.commit};
}
function feedbackText(data) {
  return data.message + "\n\n版本 " + data.version + (data.commit ? " · " + data.commit.slice(0, 12) : "");
}
function feedback(message) { $("feedback-status").textContent = message; }
async function submitFeedback() {
  const data = feedbackPayload();
  if (!data.message) { feedback("请先填写反馈。"); return; }
  if (!serverMode()) {
    const url = new URL(issuePage);
    url.searchParams.set("title", "iPhone Safari 内测反馈");
    url.searchParams.set("body", feedbackText(data));
    location.assign(url.href);
    return;
  }
  $("send-feedback").disabled = true;
  try {
    await request(apiURL("feedback"), {
      method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(data),
    });
    $("feedback-message").value = "";
    feedback("已提交反馈。");
  } catch (error) { feedback("未收到提交确认（" + error.message + "）。文字已保留，可复制给维护者。"); }
  finally { $("send-feedback").disabled = false; }
}
async function copyFeedback() {
  const data = feedbackPayload();
  if (!data.message) { feedback("请先填写反馈。"); return; }
  try {
    await navigator.clipboard.writeText(feedbackText(data));
    feedback("反馈已复制。");
  } catch {
    $("feedback-message").focus();
    $("feedback-message").select();
    feedback("请使用系统菜单复制已选文字。");
  }
}
function setup() {
  $("source").addEventListener("change", () => move(path($("source").value)));
  for (const tab of tabs) $("tab-" + tab).addEventListener("click", () => move(path(route().source, tab)));
  $("back-button").addEventListener("click", () => {
    if (history.state?.fromList) history.back();
    else move(path(route().source, route().tab), false, true);
  });
  for (const [element, kind] of [["mark-unread", "read"], ["detail-later", "later"]]) {
    $(element).addEventListener("click", () => {
      const {source, id} = route();
      if (id) library.flag(source, id, kind, !library.has(source, id, kind));
      render();
    });
  }
  $("import-file").addEventListener("change", event => importFile(event.currentTarget));
  $("clear-import").addEventListener("click", () => {
    if (!window.confirm("清除本机导入的通知和对应的阅读记录？")) return;
    library.clearLocal();
    notices.local = "已清除本机导入。";
    move(path("local"), false, true);
  });
  $("refresh-campus").addEventListener("click", refreshCampus);
  $("feedback-link").addEventListener("click", event => {
    event.preventDefault();
    $("feedback").open = true;
    $("feedback").scrollIntoView();
    $("feedback-message").focus();
  });
  $("send-feedback").addEventListener("click", submitFeedback);
  $("copy-feedback").addEventListener("click", copyFeedback);
  window.addEventListener("popstate", render);
  window.addEventListener("hashchange", render);
}
async function start() {
  setup();
  try {
    const configured = await request("./version.json");
    if (typeof configured.version === "string" && typeof configured.commit === "string") build = configured;
  } catch { /* A missing version manifest still permits local demo and import. */ }
  const server = serverMode();
  const campus = $("source").querySelector('[value="campus"]');
  campus.hidden = campus.disabled = !server;
  $("build-version").textContent = "Safari 内测 · " + build.version + " · " + (build.commit?.slice(0, 12) || "构建号未知");
  $("feedback-policy").textContent = server ? "仅在你提交后发送以上文字和构建版本。" :
    "GitHub 反馈公开可见。你可以先预览再发布，或复制给维护者。";
  $("send-feedback").textContent = server ? "提交给维护者" : "前往 GitHub 预览";
  render();
  if (server) setupPush(apiURL);
}
start();
