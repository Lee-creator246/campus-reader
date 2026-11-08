// All destinations are derived from the site's own version manifest.
const operations = new Set(["notices", "feedback", "push/config", "push/subscribe", "push/unsubscribe", "push/test"]);

export function publicApiURL(build, operation) {
  if (build?.mode !== "server" || build.api === false || !operations.has(operation)) {
    throw new Error("此页面未启用该公开服务。");
  }
  if (!Object.hasOwn(build, "publicApiOrigin")) return "./api/" + operation;
  const value = build.publicApiOrigin;
  if (typeof value === "string") {
    try {
      const url = new URL(value);
      if (url.protocol === "https:" && !url.port && url.origin === value.replace(/\/$/, "")) {
        return url.origin + "/api/" + operation;
      }
    } catch { /* Reject malformed configuration before sending a request. */ }
  }
  throw new Error("公开服务地址配置有误，请联系维护者。");
}
