import assert from "node:assert/strict";
import test from "node:test";
import {handle, originFor} from "./worker.mjs";

const worker = "https://reader.example.workers.dev";
const origin = "https://vps.example";
const env = {UPSTREAM_ORIGIN: origin, ASSETS: {fetch: () => new Response("asset")}};
const feedback = {message: "fixture", version: "0.9.1", commit: "0123456"};
const failFetch = () => assert.fail("may not contact any upstream");
const json = (value, status = 200, extras = {}) => new Response(JSON.stringify(value),
  {status, headers: {"Content-Type":"application/json", ...extras}});
const request = (path, options) => new Request(worker + path, options);
function post(path, fields, headers = {}) {
  return request(path, {method: "POST", headers: {"Origin":worker, "Content-Type":"application/json", ...headers},
    body: typeof fields === "string" ? fields : JSON.stringify(fields)});
}
test("serves static files through ASSETS without sending credentials to an upstream", async () => {
  const staticEnv = {...env, ASSETS: {fetch: r => {
    assert.equal(r.headers.get("Cookie"), null);
    return new Response("fixture asset");
  }}};
  const r = await handle(request("/reader.js", {headers: {Cookie:"private=example"}}), staticEnv, failFetch);
  assert.equal(await r.text(), "fixture asset");
});
test("denies all non-allowlisted API paths, query parameters and methods", async () => {
  for (const path of ["/api", "/api/admin", "/api/push/admin", "/api/notices?url=https://other.example"]) {
    assert.equal((await handle(request(path), env, failFetch)).status, 404);
  }
  assert.equal((await handle(request("/api/feedback"), env, failFetch)).status, 405);
  assert.equal((await handle(post("/api/notices", feedback), env, failFetch)).status, 405);
});
test("rejects cross-site or missing Origin and malformed or oversized JSON", async () => {
  for (const bad of ["null", "https://other.example", "", worker + ".evil.example"]) {
    assert.equal((await handle(post("/api/feedback", feedback, {Origin: bad}), env, failFetch)).status, 403);
  }
  assert.equal((await handle(post("/api/feedback", feedback, {"Content-Type":"text/plain"}), env, failFetch)).status, 415);
  assert.equal((await handle(post("/api/feedback", "["), env, failFetch)).status, 400);
  assert.equal((await handle(post("/api/feedback", {...feedback, password:"fixture"}), env, failFetch)).status, 400);
  assert.equal((await handle(post("/api/feedback", {...feedback,message:"字".repeat(1500)}), env, failFetch)).status, 413);
});
test("forwards only sanitized JSON and allowlisted headers to one fixed HTTPS origin", async () => {
  const inbound = post("/api/feedback", feedback, {
    Authorization:"Basic fixture", Cookie:"sid=fixture", "X-Forwarded-For":"192.0.2.10", "CF-Connecting-IP":"192.0.2.10",
  });
  const r = await handle(inbound, env, async (url, opts) => {
    assert.equal(url.href, origin + "/api/feedback");
    assert.deepEqual(opts.headers, {Accept:"application/json","Content-Type":"application/json",Origin:origin});
    assert.equal(opts.redirect, "manual");
    assert.equal(opts.method, "POST");
    assert.deepEqual(JSON.parse(opts.body), feedback);
    return json({ok:true}, 201, {"Set-Cookie":"secret=fixture", "Access-Control-Allow-Origin":"*"});
  });
  assert.equal(r.status, 201);
  assert.equal(r.headers.get("Set-Cookie"), null);
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), null);
  assert.deepEqual(await r.json(), {ok:true});
});
test("GET can fetch only public notifications and the public VAPID configuration", async () => {
  for(const path of ["/api/notices","/api/push/config"]){
    const r=await handle(request(path,{headers:{Authorization:"example"}}),env,async (url,opts)=>{
      assert.equal(url.href,origin+path);
      assert.deepEqual(opts.headers,{Accept:"application/json"});
      return json({fixture:true});
    });
    assert.equal(r.status,200);
  }
});
test("push routes forward only the endpoint and its opaque management token", async () => {
  for(const path of ["/api/push/subscribe","/api/push/unsubscribe","/api/push/test"]){
    const fields={endpoint:"https://push.example/fixture",token:"synthetic"};
    const r=await handle(post(path,fields),env,async(url,opts)=>{
      assert.equal(url.href,origin+path);
      assert.deepEqual(JSON.parse(opts.body),fields);
      return json({ok:true});
    });
    assert.equal(r.status,200);
    assert.equal((await handle(post(path,{...fields,account:"example"}),env,failFetch)).status,400);
  }
});
test("missing, unsafe, or recursive upstream cannot become an open proxy", async () => {
  for(const value of ["", "http://example.edu",origin+"/path",origin+"?route=test","https://u:p@vps.example"]){
    assert.equal(originFor(value), null);
    assert.equal((await handle(request("/api/notices"),{...env,UPSTREAM_ORIGIN:value},failFetch)).status,503);
  }
  assert.equal((await handle(request("/api/notices"),{...env,UPSTREAM_ORIGIN:worker},failFetch)).status,503);
});
test("preserves a real 503 while rejecting redirects, HTML, malformed and oversized responses", async () => {
  const r=await handle(request("/api/notices"),env,()=>json({error:"public_feed_not_configured"},503));
  assert.equal(r.status,503);
  assert.equal((await r.json()).error,"public_feed_not_configured");
  for(const result of [
    Response.redirect("https://elsewhere.example/",307),
    new Response("<html>login</html>"),
    new Response("{",{headers:{"Content-Type":"application/json"}}),
    json({blob:"x".repeat(512*1024)}),
  ]) {
    assert.equal((await handle(request("/api/notices"),env,()=>result)).status,502);
  }
});
