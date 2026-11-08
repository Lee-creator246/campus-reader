import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";
import {setTimeout as delay} from "node:timers/promises";

const code = await readFile(new URL("../public/reader-ui.js", import.meta.url), "utf8");
const {holdToPreview, dismissOnBackdrop} = await import("data:text/javascript;base64," + Buffer.from(code).toString("base64"));

function event(type, fields = {}) {
  return Object.assign(new Event(type, {cancelable:true}), {
    clientX:20, clientY:30, button:0, pointerType:"touch", isPrimary:true, ...fields,
  });
}
test("a held touch suppresses the late click that would land on the opened preview", async () => {
  const row = new EventTarget();
  let previews = 0, articles = 0;
  holdToPreview(row, () => previews++, () => articles++);
  row.dispatchEvent(event("pointerdown"));
  await delay(600);
  assert.equal(previews, 1);
  row.dispatchEvent(event("pointerup"));
  assert.equal(row.dispatchEvent(event("touchend")), false);
  row.dispatchEvent(event("click"));
  assert.equal(articles, 0);
});
test("a scrolling touch can finish normally, and an ordinary next tap opens the article", async () => {
  const row = new EventTarget();
  let previews = 0, articles = 0;
  holdToPreview(row, () => previews++, () => articles++);
  row.dispatchEvent(event("pointerdown"));
  row.dispatchEvent(event("pointermove", {clientY:80}));
  await delay(600);
  assert.equal(previews, 0);
  row.dispatchEvent(event("pointercancel"));
  assert.equal(row.dispatchEvent(event("touchend")), true);
  row.dispatchEvent(event("pointerdown"));
  row.dispatchEvent(event("pointerup"));
  assert.equal(row.dispatchEvent(event("touchend")), true);
  row.dispatchEvent(event("click"));
  assert.equal(articles, 1);
});
test("only a fresh gesture starting and ending on the backdrop dismisses the modal", () => {
  const dialog = new EventTarget();
  let closed = 0;
  dialog.close = () => { closed++; dialog.dispatchEvent(new Event("close")); };
  dialog.getBoundingClientRect = () => ({top:100,bottom:200,left:10,right:120});
  dismissOnBackdrop(dialog);
  dialog.dispatchEvent(event("click")); // Gesture originated on the underlying notice.
  dialog.dispatchEvent(event("pointerdown", {clientY:130}));
  dialog.dispatchEvent(event("click")); // Gesture started inside the modal, ended outside.
  dialog.dispatchEvent(event("pointerdown"));
  dialog.dispatchEvent(event("pointercancel"));
  dialog.dispatchEvent(event("click"));
  assert.equal(closed, 0);
  dialog.dispatchEvent(event("pointerdown"));
  dialog.dispatchEvent(event("click"));
  assert.equal(closed, 1);
});
