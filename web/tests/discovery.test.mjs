import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import test from "node:test";

const code = await readFile(new URL("../public/discovery.js", import.meta.url), "utf8");
const {TOPICS, topicsFor, rankNotice} = await import("data:text/javascript;base64," + Buffer.from(code).toString("base64"));
const notice = {title: "测试通知", body: "", publishedAt: "2026-11-03T12:00:00+08:00"};
const start = Date.parse(notice.publishedAt);
const day = 24 * 60 * 60 * 1000;

test("topics use Android order, matching literal terms in the title and plain-text body", () => {
  const item = {...notice, title: "校车与公益支教", body: "聘任、求职、困难补助、学分、大讲堂"};
  const expected = ["教学教务", "奖助评优", "志愿实践", "就业招聘", "科研竞赛", "行政公示", "生活服务"];
  assert.deepEqual(TOPICS, expected);
  assert.deepEqual(topicsFor(item), expected);
  assert.deepEqual(topicsFor({...notice, title: "奖学", body: "金"}), []);
});

test("only explicit matching topics contribute, once per topic and up to forty points", () => {
  const item = {...notice, title: "校车 食堂 招聘 课程"};
  const later = start + 4 * day;
  assert.deepEqual(rankNotice(item, {topics: ["教学教务", "教学教务", "科研竞赛"]}, later),
    {score: 20, reason: "你关注的主题：教学教务"});
  assert.deepEqual(rankNotice(item, {topics: TOPICS}, later),
    {score: 40, reason: "你关注的主题：生活服务"});
  assert.deepEqual(rankNotice(item, {topics: ["志愿实践"]}, later), {score: 0, reason: ""});
});

test("freshness follows inclusive one-day and three-day cutoffs and excludes future notices", () => {
  for (const [age, score] of [[-1, 0], [0, 6], [day, 6], [day + 1, 3], [3 * day, 3], [3 * day + 1, 0]]) {
    assert.deepEqual(rankNotice(notice, {}, start + age), {score, reason: ""});
  }
  const item = {...notice, title: "选课", publishedAt: "2026-11-03"};
  assert.deepEqual(rankNotice(item, {topics: ["教学教务"]}, Date.parse("2026-11-03T20:00:00Z")),
    {score: 26, reason: "你关注的主题：教学教务"});
});

test("reading flags and portal identifiers supply no topic or interest signals", () => {
  const item = {...notice, id: "n:100", title: "校园门户有新通知",
    body: "请在学校授权的网络环境中打开官方原文，按门户要求登录。", read: true, later: true};
  assert.deepEqual(topicsFor(item), []);
  assert.deepEqual(rankNotice(item, {topics: TOPICS}, start), {score: 6, reason: ""});
  const tagged = {...notice, title: "讲座"};
  assert.deepEqual(rankNotice({...tagged, read: true, later: true}, {}, start),
    rankNotice(tagged, {}, start));
});
