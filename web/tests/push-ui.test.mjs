import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const frontend = await readFile(new URL("../public/push.js", import.meta.url), "utf8");
const {capabilityMessage} = await import("data:text/javascript;base64," + Buffer.from(frontend).toString("base64"));
const worker = await readFile(new URL("../public/notify-worker.js", import.meta.url), "utf8");

test("iPhone requires 16.4 and a Home Screen web app; desktop uses feature detection", () => {
  const ios = {ios: true, version: [16, 4], standalone: true, secure: true, supported: true};
  assert.equal(capabilityMessage(ios), "");
  assert.match(capabilityMessage({...ios, standalone: false}), /主屏幕/);
  assert.match(capabilityMessage({...ios, version: [16, 3]}), /16\.4/);
  assert.match(capabilityMessage({...ios, secure: false}), /HTTPS/);
  assert.equal(capabilityMessage({...ios, ios: false, standalone: false}), "");
  assert.match(capabilityMessage({...ios, ios: false, supported: false}), /不支持/);
});

function workerContext(windows = []) {
  const actions = [];
  const handlers = {};
  const scope = "https://reader.example/campus-reader/";
  vm.runInNewContext(worker, {URL, self: {
    addEventListener: (name, callback) => { handlers[name] = callback; },
    registration: {scope, showNotification: async (title, options) => actions.push({title, options})},
    clients: {matchAll: async () => windows, openWindow: async url => actions.push({open: url})},
  }});
  async function fire(name, fields) {
    let done;
    handlers[name]({...fields, waitUntil: p => { done = p; }});
    assert(done, "worker must keep the event alive");
    await done;
  }
  return {actions, scope, fire};
}
test("push with no payload displays a generic notification; untrusted payload cannot change it", async () => {
  const {actions, fire, scope} = workerContext();
  await fire("push", {});
  await fire("push", {data: {json: () => ({title: "Injected", url: "https://external.example/"})}});
  assert.equal(actions.length, 2);
  assert.deepEqual(actions[0], actions[1]);
  assert.equal(actions[0].title, "北邮人信息");
  assert.equal(actions[0].options.body, "点按打开阅读页，查看公开通知。");
  assert.equal(actions[0].options.icon, scope + "icon.svg");
});
test("click opens only the public-notice route within the worker scope", async () => {
  const wrong = {url: "https://reader.example/another/", navigate: () => assert.fail("wrong scope")};
  const {actions, scope, fire} = workerContext([wrong]);
  let closed = false;
  await fire("notificationclick", {notification: {close: () => { closed = true; }, data: {url: "https://external.example/"}}});
  assert(closed);
  assert.deepEqual(actions, [{open: scope + "#campus/all"}]);
});
test("click focuses and navigates an existing reader window instead of duplicating it", async () => {
  let navigated = "", focused = false;
  const existing = {url: "https://reader.example/campus-reader/index.html#local/all",
    navigate: async url => { navigated = url; return {focus: async () => { focused = true; }}; }};
  const {actions, scope, fire} = workerContext([existing]);
  await fire("notificationclick", {notification: {close() {}}});
  assert.equal(navigated, scope + "#campus/all");
  assert(focused);
  assert.deepEqual(actions, []);
});
