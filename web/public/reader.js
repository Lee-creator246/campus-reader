import { Library, MAX_BYTES, SOURCE_NAMES, noticesFrom } from "./data.js";
import { setupPush } from "./push.js";
import { publicApiURL } from "./api-url.js";

import { TOPICS, topicsFor, rankNotice } from "./discovery.js";
import { keywordMatch, loadKeywordRules, mountKeywordRules } from "./keyword-rules.js";
import { glyph, loadPhoto, holdToPreview, activateDialog, dismissOnBackdrop } from "./reader-ui.js";

const $ = id => document.getElementById(id);
const library = new Library();
const tabs = ["recommended", "all", "unread", "later"];
const OPTIONS_KEY = "campus-reader.safari.options.1";
let layout = "magazine", selectedTopic = "", previewed;
let interests = new Set();
let keywordRules = loadKeywordRules();
try {
  const selected = JSON.parse(localStorage.getItem(OPTIONS_KEY) || "{}");
  if (["list","magazine","card"].includes(selected.layout)) layout = selected.layout;
  if (Array.isArray(selected.topics)) interests = new Set(selected.topics.filter(topic => TOPICS.includes(topic)));
} catch { library.notice = "本机阅读偏好暂时无法读取。"; }
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
  const tab = tabs.includes(parts[1]) || parts[1] === "focus" ? parts[1] : "recommended";
  let id = "";
  try { id = decodeURIComponent(parts.slice(2).join("/")); } catch { /* Invalid deep link shows the list. */ }
  return {source, tab, id};
}
function path(source, tab = "recommended", id = "") {
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
function chip(label, pressed, action) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  button.setAttribute("aria-pressed", String(pressed));
  button.addEventListener("click", action);
  return button;
}

function renderTopicChoices() {
  const filters = ["", ...TOPICS].map(topic => chip(topic || "全部标签", topic === selectedTopic, event => {
    selectedTopic = topic;
    render();
    $("topics").children[["", ...TOPICS].indexOf(topic)]?.focus({preventScroll: true});
  }));
  $("topics").replaceChildren(...filters);
  $("interests").replaceChildren(...TOPICS.map(topic => chip(topic, interests.has(topic), () => {
    if (interests.has(topic)) interests.delete(topic); else interests.add(topic);
    saveOptions();
    render();
    $("interests").children[TOPICS.indexOf(topic)]?.focus({preventScroll: true});
  })));
}
function saveOptions() {
  try { localStorage.setItem(OPTIONS_KEY, JSON.stringify({layout, topics: [...interests]})); }
  catch { library.notice = "当前浏览器无法保存阅读偏好。"; }
}

function filteredRows(source, tab) {
  const term = $("search").value.trim().toLocaleLowerCase();
  const ranked = [];
  const now = Date.now();
  const profile = {topics: [...interests]};
  for (const item of library.feeds[source]) {
    const match = keywordMatch(item, keywordRules);
    if (match.blocked || (tab === "focus" && !match.priority)) continue;
    if (tab === "unread" && library.has(source, item.id, "read")) continue;
    if (tab === "later" && !library.has(source, item.id, "later")) continue;
    const topics = topicsFor(item);
    if (selectedTopic && !topics.includes(selectedTopic)) continue;
    if (term && !(item.title + "\n" + item.body).toLocaleLowerCase().includes(term)) continue;
    const rank = tab === "recommended" ? rankNotice(item, profile, now) : {score: 0, reason: ""};
    ranked.push({item, topics, priority: match.priority, score: rank.score + match.score,
      reason: tab === "recommended" || tab === "focus" ? match.reason || rank.reason : ""});
  }
  if (tab === "recommended" && (interests.size || ranked.some(row => row.priority))) {
    ranked.sort((a, b) => b.score - a.score || Date.parse(b.item.publishedAt) - Date.parse(a.item.publishedAt));
  }
  return ranked;
}

function makeRow({item, topics, reason}, source, tab) {
  const li = document.createElement("li");
  const anchor = document.createElement("a");
  anchor.className = "row";
  anchor.href = path(source, tab, item.id);
  anchor.dataset.noticeId = item.id;
  const read = library.has(source, item.id, "read");
  const later = library.has(source, item.id, "later");
  anchor.dataset.read = String(read);
  const copy = document.createElement("div");
  copy.className = "row-copy";
  span(copy, "row-title", item.title);
  if (reason || topics.length) span(copy, "row-topic", reason || topics.join(" · "));
  const meta = document.createElement("div");
  meta.className = "row-meta";
  span(meta, "", SOURCE_NAMES[source]);
  span(meta, "", dateText(item.publishedAt));
  span(meta, "", read ? "已读" : "未读");
  if (later) {
    meta.append(glyph("bookmark"));
  }
  anchor.setAttribute("aria-label", [item.title, SOURCE_NAMES[source], dateText(item.publishedAt),
    read ? "已读" : "未读", later ? "稍后读" : ""].filter(Boolean).join("，"));
  copy.append(meta);
  anchor.append(copy);
  loadPhoto(anchor, item.image, {thumbnail:true});
  holdToPreview(anchor, () => openPreview(source, tab, item), () => move(anchor.hash, true));
  li.append(anchor);
  return li;
}

function showList(source, tab) {
  const list = library.feeds[source];
  const visible = filteredRows(source, tab);
  $("count").textContent = "已显示 " + visible.length + " / " + list.length + " 篇";
  $("notices").dataset.layout = layout;
  $("notices").replaceChildren(...visible.map(row => makeRow(row, source, tab)));
  for (const key of tabs) {
    const button = $("tab-" + key);
    if (key === tab) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  }
  $("mark-visible").disabled = !visible.some(row => !library.has(source, row.item.id, "read"));
  const personalReason = visible.some(row => row.reason);
  $("rec-note").textContent = tab === "recommended" ? (personalReason ? "按关注推荐" : "按发布时间排序") :
    tab === "focus" ? "匹配优先提醒词" : "";
  let message = "";
  if (!visible.length) {
    if (keywordRules.loadFailed) message = "关键词规则无法读取，请在导航栏「关键词规则」中重新读取或重置。";
    else if (source === "local" && !list.length) message = "请在导航栏「阅读来源」中导入通知。";
    else if (source === "campus" && !list.length) message = "当前没有公开通知可显示。";
    else if (selectedTopic || $("search").value.trim()) message = "没有匹配的通知。可更改主题或搜索词。";
    else if (tab === "unread") message = "当前没有未读通知。";
    else if (tab === "later") message = "还没有加入稍后读的通知。";
    else if (tab === "focus") message = "未找到匹配的通知，可在导航栏「关键词规则」中设置优先提醒词。";
    else message = "当前没有符合阅读规则的通知。";
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
  const read = library.has(source, item.id, "read");
  $("detail-later").setAttribute("aria-pressed", String(saved));
  $("detail-later").setAttribute("aria-label", saved ? "取消稍后读" : "稍后读");
  $("mark-unread").replaceChildren(glyph(read ? "unread" : "check"), document.createTextNode(read ? "未读" : "已读"));
  $("mark-unread").setAttribute("aria-label", read ? "标为未读" : "标为已读");
  $("original").hidden = !item.url;
  if (item.url) $("original").href = item.url;
  else $("original").removeAttribute("href");
  if (entering) {
    $("detail-media").replaceChildren();
    $("detail-media").dataset.photo = "";
    loadPhoto($("detail-media"), item.image);
    window.scrollTo(0, 0);
    $("detail-title").focus({preventScroll:true});
  }
}

function render() {
  const {source, tab, id} = route();
  $("source").value = source;
  $("source-description").textContent = source === "demo" ? "合成通知，用于体验阅读流程。" :
    source === "local" ? "通知仅保存在此浏览器。新文件会替换导入列表。" :
    "仅显示获准公开的通知。" + (library.updated ? "上次刷新：" + new Date(library.updated).toLocaleString("zh-CN") : "");
  $("feed-label").textContent = source === "demo" ? "演示数据 · 合成通知" : SOURCE_NAMES[source];
  $("local-tools").hidden = source !== "local";
  status($("import-message"), source === "local" ? notices.local : "");
  $("clear-import").hidden = !library.feeds.local.length;
  $("refresh-campus").hidden = source !== "campus";
  $("refresh-campus").disabled = loadingCampus;
  const item = id ? library.feeds[source].find(n => n.id === id) : null;
  const missing = id && !item && !loadingCampus;
  status($("source-status"), missing ? "这条通知不在当前来源中，请从列表选择。" : notices[source]);
  $("list-view").hidden = Boolean(item);
  $("detail-view").hidden = !item;
  $("open-nav").hidden = Boolean(item);
  $("list-tools").hidden = Boolean(item);
  $("back-button").hidden = !item;
  $("bar-title").textContent = item ? SOURCE_NAMES[source] : tab === "focus" ? "重点关注" : "北邮人信息";
  document.body.classList.toggle("in-detail", Boolean(item));
  document.title = (item ? item.title + " · " : "") + "北邮人信息 · Safari 内测";
  if (item) showDetail(source, item);
  else {
    const leaving = Boolean(currentDetail);
    currentDetail = "";
    renderTopicChoices();
    showList(source, tab);
    if (leaving) window.scrollTo(0, scrollPositions.get(path(source, tab)) || 0);
  }
  const available = library.feeds[source].map(item => ({item, match: keywordMatch(item, keywordRules)})).filter(row => !row.match.blocked);
  $("drawer-unread").textContent = available.filter(({item}) => !library.has(source,item.id,"read")).length || "";
  $("drawer-later").textContent = available.filter(({item}) => library.has(source,item.id,"later")).length || "";
  $("drawer-focus").textContent = available.filter(({match}) => match.priority).length || "";
  for (const b of document.querySelectorAll(".drawer-nav [data-jump]")) {
    if (b.dataset.jump === tab) b.setAttribute("aria-current","page"); else b.removeAttribute("aria-current");
  }
  for (const b of document.querySelectorAll(".options [data-jump]")) b.setAttribute("aria-pressed",String(tab === b.dataset.jump));
  for (const b of document.querySelectorAll("[data-mode]")) b.setAttribute("aria-pressed",String(layout === b.dataset.mode));
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
    $("navigation").close();
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
function openPreview(source, tab, item) {
  previewed = {source, tab, item};
  $("preview-title").textContent = item.title;
  $("preview-meta").textContent = dateText(item.publishedAt) + " · 预览不标为已读";
  $("preview-copy").textContent = item.body.length > 320 ? item.body.slice(0, 320) + "…" : item.body || "此通知没有附带正文。";
  updatePreviewActions();
  activateDialog($("preview"), $("close-preview"));
}
function updatePreviewActions() {
  const {source, item} = previewed;
  $("preview-later").textContent = library.has(source, item.id, "later") ? "取消稍后读" : "稍后读";
  $("preview-read").textContent = library.has(source, item.id, "read") ? "标为未读" : "标为已读";
}
function resetFilters() { selectedTopic = ""; $("search").value = ""; }
function toggleSearch(open) {
  $("search-panel").hidden = !open;
  $("open-search").setAttribute("aria-expanded", String(open));
  if (open) $("search").focus();
  else { $("search").value = ""; render(); $("open-search").focus(); }
}
function setup() {
  const nav = $("navigation"), options = $("reading-options"), peek = $("preview");
  for (const dialog of [nav, options, peek]) dismissOnBackdrop(dialog);
  $("open-nav").addEventListener("click", () => activateDialog(nav, $("close-nav")));
  $("choose-source").addEventListener("click", () => activateDialog(nav, $("source")));
  $("close-nav").addEventListener("click", () => nav.close());
  $("open-options").addEventListener("click", () => activateDialog(options));
  $("close-options").addEventListener("click", () => options.close());
  $("close-preview").addEventListener("click", () => peek.close());
  $("preview-open").addEventListener("click", () => {
    const {source, tab, item} = previewed;
    peek.close();
    move(path(source, tab, item.id), true);
  });
  for (const [id, kind] of [["preview-read","read"],["preview-later","later"]]) {
    $(id).addEventListener("click", () => {
      const {source, item} = previewed;
      library.flag(source, item.id, kind, !library.has(source, item.id, kind));
      updatePreviewActions();
      render();
    });
  }
  $("source").addEventListener("change", () => {
    resetFilters();
    const source = $("source").value;
    move(path(source));
    if (source !== "local") nav.close();
  });
  for (const tab of tabs) $("tab-" + tab).addEventListener("click", () => move(path(route().source, tab)));
  for (const b of document.querySelectorAll("[data-jump]")) {
    b.addEventListener("click", () => { b.closest("dialog").close(); resetFilters(); move(path(route().source, b.dataset.jump)); });
  }
  for (const b of document.querySelectorAll("[data-mode]")) {
    b.addEventListener("click", () => { layout = b.dataset.mode; saveOptions(); options.close(); render(); });
  }
  $("reset-filter").addEventListener("click", () => { resetFilters(); options.close(); render(); });
  $("open-search").addEventListener("click", () => toggleSearch($("search-panel").hidden));
  $("close-search").addEventListener("click", () => toggleSearch(false));
  $("search").addEventListener("input", render);
  $("mark-visible").addEventListener("click", () => {
    const {source, tab} = route();
    const rows = filteredRows(source, tab).map(row => row.item).filter(item => !library.has(source, item.id, "read"));
    if (rows.length && window.confirm("将当前列表中的 " + rows.length + " 篇未读通知标为已读？")) {
      library.markRead(source, rows);
      render();
    }
  });
  mountKeywordRules($("keyword-rules-editor"), keywordRules, next => { keywordRules = next; render(); });
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
  $("refresh-campus").addEventListener("click", () => { nav.close(); refreshCampus(); });
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
