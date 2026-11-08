const RECORD = "campus-reader.push-management.1";
const $ = id => document.getElementById(id);

export function capabilityMessage({ios, version, standalone, secure, supported}) {
  if (!secure) return "请通过 HTTPS 打开内测地址。";
  if (ios && version && (version[0] < 16 || (version[0] === 16 && version[1] < 4))) {
    return "iPhone 和 iPad 需要 iOS / iPadOS 16.4 或以上版本。";
  }
  if (ios && !standalone) return "点 Safari 的分享按钮，选择“添加到主屏幕”，再从主屏幕图标打开并订阅。";
  if (!supported) return "当前浏览器不支持网页推送，请在支持网页推送的浏览器或主屏幕 App 中打开。";
  return "";
}

function capability() {
  const ua = navigator.userAgent;
  const version = ua.match(/OS (\d+)[_.](\d+)/);
  return capabilityMessage({
    ios: /iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1),
    version: version ? [Number(version[1]), Number(version[2])] : null,
    standalone: matchMedia("(display-mode: standalone)").matches || navigator.standalone === true,
    secure: window.isSecureContext,
    supported: "serviceWorker" in navigator && "PushManager" in window && "Notification" in window &&
      "ServiceWorkerRegistration" in window && "showNotification" in ServiceWorkerRegistration.prototype,
  });
}

function storedRecord() {
  const record = JSON.parse(localStorage.getItem(RECORD) || "null");
  if (!record) return null;
  if (typeof record.endpoint !== "string" || !/^[a-f0-9]{64}$/.test(record.token)) {
    throw new Error("本机订阅记录无法读取。");
  }
  return record;
}
function writeRecord(record) {
  if (record) localStorage.setItem(RECORD, JSON.stringify(record));
  else localStorage.removeItem(RECORD);
}
function newToken() {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), n => n.toString(16).padStart(2, "0")).join("");
}
function decodeKey(key) {
  if (typeof key !== "string") throw new Error("服务推送公钥不可用。");
  const bytes = Uint8Array.from(atob(key.replace(/-/g, "+").replace(/_/g, "/")), c => c.charCodeAt(0));
  if (bytes.length !== 65 || bytes[0] !== 4) throw new Error("服务推送公钥不可用。");
  return bytes;
}
const messages = {
  invalid_management_token: "本机管理凭据与此订阅不匹配。请取消后重新订阅。",
  endpoint_already_managed: "此订阅已绑定其他管理凭据。请取消本机订阅后重试。",
  push_rate_limit: "测试过于频繁，请稍后重试。",
  push_service_rejected: "推送网关未接受这次请求。",
  push_unavailable: "提醒服务暂时不可用。",
  subscription_capacity: "当前内测订阅名额已满。",
  subscription_expired: "本机推送订阅已失效。请取消后重新订阅。",
};
async function api(name, record) {
  const controller = new AbortController();
  // Leave time to receive the gateway's response before treating a write as indeterminate.
  const timer = setTimeout(() => controller.abort(), 22000);
  try {
    const options = {cache: "no-store", redirect: "error", credentials: "omit", signal: controller.signal};
    if (record) {
      options.method = "POST";
      options.headers = {"Content-Type": "application/json"};
      options.body = JSON.stringify({endpoint: record.endpoint, token: record.token});
    }
    const response = await fetch("./api/push/" + name, options);
    if (!response.headers.get("Content-Type")?.includes("application/json")) throw new Error("服务没有返回有效的确认信息。");
    const result = await response.json();
    if (!response.ok) {
      const error = new Error(messages[result.error] || "请求未完成（HTTP " + response.status + "）。");
      error.code = result.error;
      throw error;
    }
    return result;
  } finally { clearTimeout(timer); }
}
async function readyWorker() {
  await navigator.serviceWorker.register("./notify-worker.js", {scope: "./", updateViaCache: "none"});
  let timer;
  try {
    return await Promise.race([navigator.serviceWorker.ready, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error("通知组件启动超时，请重新检查。")), 12000);
    })]);
  } finally { clearTimeout(timer); }
}
function message(error) {
  if (error.name === "NotAllowedError") return "系统未允许提醒。可在系统通知设置中启用后再试。";
  if (error.name === "InvalidStateError") return "推送配置已变化，请取消后重新订阅。";
  if (error.name === "QuotaExceededError" || error.name === "SecurityError") return "浏览器无法保存订阅记录，请检查存储和通知权限。";
  if (error.name === "AbortError" || error instanceof TypeError) return "网络请求未完成，请检查连接后重试。";
  return error.message || "提醒设置未完成，请重试。";
}

export function setupPush() {
  $("push-settings").hidden = false;
  const say = text => { $("push-status").textContent = text; };
  const unavailable = capability();
  if (unavailable) { say(unavailable); return; }

  let registration, subscription, key, record;
  let busy = false;

  function controls(note) {
    const matches = record && subscription && record.endpoint === subscription.endpoint;
    const active = Boolean(matches && record.confirmed && !record.removing);
    const stale = Boolean(record && (!matches || record.removing));
    $("push-enable").hidden = !key || active || stale || Notification.permission === "denied";
    $("push-test").hidden = !active || !key || Notification.permission !== "granted";
    $("push-disable").hidden = !record && !subscription;
    $("push-retry").hidden = Boolean(key) && Notification.permission !== "denied";
    for (const name of ["enable", "test", "disable", "retry"]) $("push-" + name).disabled = busy;
    say(note || (stale ? "请完成旧订阅的取消，再按需重新开启。" :
      Notification.permission === "denied" ? "系统已关闭通知权限。如需开启，请到系统通知设置中调整。" :
      active ? "此设备已订阅，可发送测试提醒。" : "点“开启提醒”后，按系统提示确认。"));
  }

  async function inspect() {
    key = null;
    registration = await readyWorker();
    subscription = await registration.pushManager.getSubscription();
    record = storedRecord();
    const config = await api("config");
    if (!config.enabled) return "当前服务没有开启手机提醒。";
    key = decodeKey(config.publicKey);
    return "";
  }

  async function enable() {
    // Invoke subscribe directly from the user click; iOS requires a user gesture to show consent.
    subscription = subscription || await registration.pushManager.subscribe({userVisibleOnly: true, applicationServerKey: key});
    const existing = storedRecord();
    if (existing && existing.endpoint !== subscription.endpoint) throw new Error("请先取消旧订阅。");
    record = existing || {endpoint: subscription.endpoint, token: newToken(), confirmed: false};
    writeRecord(record); // Preserve the management token before a potentially indeterminate network request.
    const result = await api("subscribe", record);
    if (result.ok !== true) throw new Error("服务尚未确认订阅，可再次点击开启。");
    record.confirmed = true;
    writeRecord(record);
    return "已订阅。点“发送测试提醒”可检查本机是否收到通知。";
  }

  async function disable() {
    record = storedRecord() || record;
    if (record) { record.removing = true; writeRecord(record); }
    if (subscription) {
      const canceled = await subscription.unsubscribe();
      if (!canceled && await registration.pushManager.getSubscription()) throw new Error("浏览器尚未取消订阅，请重试。");
      subscription = null;
    }
    if (record) {
      try {
        if ((await api("unsubscribe", record)).ok !== true) throw new Error("请重试取消服务记录。");
      } catch (error) {
        // A stale local token cannot delete someone else's record; the local endpoint is disabled.
        if (!["invalid_management_token", "subscription_expired"].includes(error.code)) {
          return "本机已停止提醒，服务端清理尚未确认。恢复连接后请再次点取消订阅。";
        }
      }
      writeRecord(null);
      record = null;
    }
    return "本机已取消订阅。";
  }

  async function test() {
    const result = await api("test", record);
    if (result.accepted !== true) throw new Error("推送服务尚未确认，请稍后重试。");
    return "推送服务已受理。请查看本机通知中心确认是否收到，并可点通知返回阅读页。";
  }

  function run(action) {
    if (busy) return;
    busy = true;
    controls("正在处理…");
    action().then(note => { busy = false; controls(note); }, error => { busy = false; controls(message(error)); });
  }
  $("push-enable").addEventListener("click", () => run(enable));
  $("push-test").addEventListener("click", () => run(test));
  $("push-disable").addEventListener("click", () => run(disable));
  $("push-retry").addEventListener("click", () => run(inspect));
  // Preparing the worker and capability configuration requests no notification permission.
  run(inspect);
}
