import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";

const code = await readFile(new URL("../public/api-url.js", import.meta.url), "utf8");
const {publicApiURL} = await import("data:text/javascript;base64," + Buffer.from(code).toString("base64"));
const server = {mode: "server", api: true};

test("all six server calls use either the configured fixed origin or the relative API", () => {
  for (const name of ["notices", "feedback", "push/config", "push/subscribe", "push/unsubscribe", "push/test"]) {
    assert.equal(publicApiURL(server, name), "./api/" + name);
    assert.equal(publicApiURL({...server, publicApiOrigin: "https://vps.example/"}, name),
      "https://vps.example/api/" + name);
  }
});
test("invalid configured origins fail closed without a relative retry", () => {
  for (const origin of ["", null, "http://vps.example", "https://user@vps.example",
    "https://vps.example:443", "https://vps.example:8443", "https://vps.example/a", "https://vps.example?u=1",
    "https://vps.example/#token", "//other.example", "https://vps.example\\other", " https://vps.example"]) {
    assert.throws(() => publicApiURL({...server, publicApiOrigin: origin}, "feedback"));
  }
});
test("static sites and caller-provided paths can never issue an API request", () => {
  assert.throws(() => publicApiURL({mode: "static", publicApiOrigin: "https://vps.example"}, "notices"));
  assert.throws(() => publicApiURL({...server, api:false}, "feedback"));
  for (const name of ["https://other.example", "../api/admin", "notices?origin=https://other.example"]) {
    assert.throws(() => publicApiURL(server, name));
  }
});
