// School content is always rendered as text, including imported titles and bodies.
export const MAX_BYTES = 512 * 1024;
export const SOURCE_NAMES = {
  demo: "演示数据",
  local: "本机导入",
  campus: "公开通知",
};
export const EXAMPLES = [
  { id: "sample-001", publishedAt: "2026-11-03", title: "志愿服务报名通知",
    body: "【合成通知 · 阅读测试】\n\n本次志愿服务拟开放图书整理和活动引导两个岗位。请根据时间安排选择岗位，并于活动开始前到集合点签到。\n\n此条内容仅用于测试通知阅读，不接受实际报名。" },
  { id: "sample-002", publishedAt: "2026-11-02", title: "图书馆周末开放时间调整",
    body: "【合成通知 · 阅读测试】\n\n本周末部分阅览区将分时开放。预约阅览座位前，请先查看对应区域的开放安排。\n\n具体日期与开放时间以学校正式通知为准。" },
  { id: "sample-003", publishedAt: "2026-11-01", title: "关于开展实验室安全培训的通知",
    body: "【合成通知 · 阅读测试】\n\n本次培训包括实验室日常检查、仪器操作和应急处置。参加同学需完成线上学习，并确认培训记录。\n\n此示例不代表已有校内培训安排。" },
];

function requiredString(value, limit, label) {
  if (typeof value !== "string" || !value.trim() || value.length > limit) {
    throw new Error(label + "为空或长度超出范围");
  }
  return value.trim();
}

function schoolArticleURL(value) {
  if (value == null || value === "") return "";
  let url;
  try { url = new URL(value); } catch { throw new Error("原文地址无效"); }
  if (!["http:", "https:"].includes(url.protocol) || url.hostname !== "my.bupt.edu.cn" ||
      url.username || url.password || url.port || url.hash) {
    throw new Error("原文地址须属于学校信息门户");
  }
  return url.href;
}

export function safeImage(value) {
  if (typeof value !== "string" || value.length > 2048 || !value.startsWith("https://") ||
      /[\s\\?#\u0000-\u001f\u007f-\u009f]/.test(value)) return "";
  try {
    const url = new URL(value);
    if (url.hostname === "my.bupt.edu.cn" && !url.port && !url.username && !url.password) return url.href;
  } catch { /* An invalid thumbnail never invalidates a text notice. */ }
  return "";
}

export function noticesFrom(payload) {
  if (!payload || !Array.isArray(payload.notices) || payload.notices.length > 200) {
    throw new Error("需要 notices 数组，最多 200 条");
  }
  const items = new Map();
  payload.notices.forEach((entry, index) => {
    try {
      if (!entry || typeof entry !== "object") throw new Error("记录无效");
      const id = requiredString(entry.id, 160, "编号");
      if (/[\u0000-\u001f]/.test(id)) throw new Error("编号包含控制字符");
      try { encodeURIComponent(id); } catch { throw new Error("编号包含无效字符"); }
      const title = requiredString(entry.title, 500, "标题");
      const publishedAt = requiredString(entry.publishedAt, 40, "日期");
      if (!/^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2}))?$/.test(publishedAt) ||
          !Number.isFinite(Date.parse(publishedAt))) throw new Error("日期须采用 ISO 8601 格式");
      if (typeof entry.body !== "string" || entry.body.length > 160000) throw new Error("正文格式有误");
      const row = {id, title, publishedAt, body: entry.body, url: schoolArticleURL(entry.url), image: safeImage(entry.image)};
      items.set(id, row);
    } catch (error) { throw new Error("第 " + (index + 1) + " 条：" + error.message); }
  });
  return Array.from(items.values()).sort((a, b) =>
    Date.parse(b.publishedAt) - Date.parse(a.publishedAt) || a.id.localeCompare(b.id));
}

const KEY = "campus-reader.safari.1";
const storageNote = "当前浏览器无法保存修改。关闭页面后，本次阅读记录可能丢失。";
export class Library {
  constructor() {
    this.notice = "";
    this.feeds = {demo: noticesFrom({notices: EXAMPLES}), local: [], campus: []};
    this.read = new Map();
    this.later = new Map();
    this.updated = "";
    try {
      const raw = localStorage.getItem(KEY);
      if (!raw) return;
      const data = JSON.parse(raw);
      const local = noticesFrom({notices: data.local});
      const campus = noticesFrom({notices: data.campus});
      for (const [target, rows] of [[this.read, data.read], [this.later, data.later]]) {
        for (const item of Array.isArray(rows) ? rows.slice(-1200) : []) {
          if (typeof item === "string" && item.length <= 170) target.set(item, true);
        }
      }
      this.feeds.local = local;
      this.feeds.campus = campus;
      this.updated = typeof data.updated === "string" ? data.updated : "";
    } catch { this.notice = "上次的本机缓存暂时无法读取。"; }
  }
  save() {
    try {
      localStorage.setItem(KEY, JSON.stringify({
        local: this.feeds.local, campus: this.feeds.campus, updated: this.updated,
        read: Array.from(this.read.keys()).slice(-1200), later: Array.from(this.later.keys()).slice(-1200),
      }));
      this.notice = "";
    } catch { this.notice = storageNote; }
  }
  flag(source, id, kind, value) {
    const map = kind === "read" ? this.read : this.later;
    const key = source + ":" + id;
    if (map.has(key) === value) return;
    if (value) map.set(key, true); else map.delete(key);
    if (map.size > 1200) map.delete(map.keys().next().value);
    this.save();
  }
  has(source, id, kind) {
    return (kind === "read" ? this.read : this.later).has(source + ":" + id);
  }
  markRead(source, rows) {
    for (const row of rows) this.read.set(source + ":" + row.id, true);
    while (this.read.size > 1200) this.read.delete(this.read.keys().next().value);
    this.save();
  }
  clearLocal() {
    this.feeds.local = [];
    for (const map of [this.read, this.later]) {
      for (const key of map.keys()) if (key.startsWith("local:")) map.delete(key);
    }
    this.save();
  }
}
