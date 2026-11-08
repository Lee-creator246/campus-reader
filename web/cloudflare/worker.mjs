const routes = new Map([
  ["/api/notices", null],
  ["/api/push/config", null],
  ["/api/feedback", ["message", "version", "commit"]],
  ["/api/push/subscribe", ["endpoint", "token"]],
  ["/api/push/unsubscribe", ["endpoint", "token"]],
  ["/api/push/test", ["endpoint", "token"]],
]);
const jsonHeaders = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
};

function reject(status, code) {
  return new Response(JSON.stringify({error: code}), {status, headers: jsonHeaders});
}

export function originFor(value) {
  try {
    const url = new URL(value);
    if (url.protocol === "https:" && url.hostname && !url.username && !url.password &&
        url.pathname === "/" && !url.search && !url.hash && !url.port) return url.origin;
  } catch { /* A missing or invalid operator binding leaves the demo readable. */ }
  return null;
}

async function readJSON(message, limit) {
  if (Number(message.headers.get("Content-Length")) > limit) throw new RangeError();
  const reader = message.body?.getReader();
  if (!reader) throw new SyntaxError();
  const bytes = new Uint8Array(limit);
  let size = 0;
  try {
    for (;;) {
      const {done, value} = await reader.read();
      if (done) break;
      if (size + value.byteLength > limit) {
        await reader.cancel();
        throw new RangeError();
      }
      bytes.set(value, size);
      size += value.byteLength;
    }
  } finally { reader.releaseLock(); }
  return JSON.parse(new TextDecoder("utf-8", {fatal: true}).decode(bytes.subarray(0, size)));
}

function isJson(message) {
  return message.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase() === "application/json";
}

export async function handle(request, env, upstreamFetch = fetch) {
  const url = new URL(request.url);
  if (url.pathname !== "/api" && !url.pathname.startsWith("/api/")) {
    if (!["GET", "HEAD"].includes(request.method)) return reject(405, "method_not_allowed");
    return env.ASSETS.fetch(new Request(url.href, {method: request.method}));
  }
  if (!routes.has(url.pathname) || url.search) return reject(404, "not_found");
  const fields = routes.get(url.pathname);
  if (request.method !== (fields ? "POST" : "GET")) return reject(405, "method_not_allowed");
  if (fields && request.headers.get("Origin") !== url.origin) return reject(403, "wrong_origin");
  if (fields && !isJson(request)) return reject(415, "json_required");

  const origin = originFor(env.UPSTREAM_ORIGIN);
  if (!origin || origin === url.origin) return reject(503, "upstream_not_configured");
  const headers = {"Accept": "application/json"};
  let body;
  if (fields) {
    let input;
    try { input = await readJSON(request, 4096); }
    catch (err) { return err instanceof RangeError ? reject(413, "request_too_large") : reject(400, "invalid_json"); }
    if (!input || typeof input !== "object" || Array.isArray(input) ||
        Object.keys(input).length !== fields.length || !fields.every(k => typeof input[k] === "string")) {
      return reject(400, "invalid_fields");
    }
    body = JSON.stringify(input);
    if (new TextEncoder().encode(body).length > 4096) return reject(413, "request_too_large");
    headers["Content-Type"] = "application/json";
    // Only after verifying the browser's Origin, identify this adapter to the VPS origin guard.
    headers.Origin = origin;
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 16000);
  try {
    const upstream = await upstreamFetch(new URL(url.pathname, origin), {
      method: request.method, headers, body, redirect: "manual", signal: controller.signal,
    });
    if (upstream.status >= 300 && upstream.status < 400 || !isJson(upstream)) return reject(502, "unexpected_upstream_response");
    let json;
    try { json = await readJSON(upstream, 512 * 1024); }
    catch (err) {
      if (controller.signal.aborted) throw err;
      return reject(502, "invalid_upstream_json");
    }
    if (!json || typeof json !== "object" || Array.isArray(json)) return reject(502, "invalid_upstream_json");
    return new Response(JSON.stringify(json), {status: upstream.status, headers: jsonHeaders});
  } catch {
    return controller.signal.aborted ? reject(504, "upstream_timeout") : reject(502, "upstream_unavailable");
  }
  finally { clearTimeout(timeout); }
}

export default {fetch(request, env) { return handle(request, env); }};
