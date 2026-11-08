import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";

const code = await readFile(new URL("../public/keyword-rules.js", import.meta.url), "utf8");
const {keywordMatch, loadKeywordRules, parseKeywordTerms, saveKeywordRules}
  = await import("data:text/javascript;base64," + Buffer.from(code).toString("base64"));
const empty = {blockedEnabled: false, blockedWords: [], priorityEnabled: false, priorityWords: []};
const notice = {title: "科研 Campus", body: "提供志愿服务"};
const rules = {...empty, blockedEnabled: true, blockedWords: ["内部"],
  priorityEnabled: true, priorityWords: ["志愿", "CAMPUS"]};
const noMatch = {blocked: false, priority: false, score: 0, reason: ""};

test("terms preserve first spelling, internal spaces and literal punctuation", () => {
  assert.deepEqual(parseKeywordTerms(" CaMpUs，campus \r\n暑期 实习,[活动].*"), ["CaMpUs", "暑期 实习", "[活动].*"]);
  assert.deepEqual(parseKeywordTerms("Ä,ä"), ["Ä", "ä"]);
  assert.deepEqual(parseKeywordTerms("，，\n"), []);
  assert.throws(() => parseKeywordTerms("内\t部"), /控制/);
  assert.deepEqual(parseKeywordTerms("🌿".repeat(100)), ["🌿".repeat(100)]);
  assert.throws(() => parseKeywordTerms("🌿".repeat(101)), /100/);
  assert.throws(() => parseKeywordTerms(Array.from({length: 51}, (_, i) => String(i)).join(",")), /50/);
});

test("blocking overrides attention and title matches take precedence over body matches", () => {
  assert.deepEqual(keywordMatch(notice, rules),
    {blocked: false, priority: true, score: 70, reason: "标题匹配关注词：CAMPUS"});
  assert.deepEqual(keywordMatch({...notice, title: "测试"}, rules),
    {blocked: false, priority: true, score: 35, reason: "匹配关注词：志愿"});
  assert.deepEqual(keywordMatch({...notice, body: "内部志愿"}, rules), {...noMatch, blocked: true});
  assert.deepEqual(keywordMatch({...notice, title: "内部"}, {...rules, blockedEnabled: false, priorityEnabled: false}), noMatch);
  assert.deepEqual(keywordMatch(notice, {...rules, priorityWords: [".*"]}), noMatch);
});

test("saving and disabling rules keeps the terms and touches only their local storage key", () => {
  const data = new Map([["reader-search", "临时搜索"], ["reader-notices", "缓存记录"]]);
  const storage = {getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value)};
  assert.deepEqual(loadKeywordRules(storage), empty);
  saveKeywordRules(rules, storage);
  assert.deepEqual(loadKeywordRules(storage), rules);
  const paused = {...rules, blockedEnabled: false, priorityEnabled: false};
  saveKeywordRules(paused, storage);
  assert.deepEqual(loadKeywordRules(storage), paused);
  assert.throws(() => saveKeywordRules({...paused, blockedEnabled: true, blockedWords: []}, storage), /请填写屏蔽词/);
  assert.deepEqual(loadKeywordRules(storage), paused);
  assert.equal(data.size, 3);
  assert.equal(data.get("reader-search"), "临时搜索");
  assert.equal(data.get("reader-notices"), "缓存记录");
});

test("invalid stored rules pause visibility and ordinary save does not replace them", () => {
  for (const raw of ["", "null", '{"blockedWords":[]}', JSON.stringify({...rules, blockedWords: [null]})]) {
    let writes = 0;
    const storage = {getItem: () => raw, setItem: () => { writes++; }};
    const broken = loadKeywordRules(storage);
    assert.equal(broken.loadFailed, true);
    assert.deepEqual(keywordMatch(notice, broken), {...noMatch, blocked: true});
    assert.throws(() => saveKeywordRules(rules, storage), /重置/);
    assert.equal(writes, 0);
  }
});
