// Keep the topic order and literal terms in sync with Android's RecommendationEngine.
const termsByTopic = {
  教学教务: ["教务", "教学", "选课", "考试", "课程", "学籍", "培养", "学分", "毕业", "补考"],
  奖助评优: ["奖学金", "助学", "资助", "评优", "评奖", "荣誉", "困难补助"],
  志愿实践: ["志愿", "社会实践", "实践活动", "支教", "公益"],
  就业招聘: ["招聘", "就业", "实习", "宣讲会", "双选会", "求职"],
  科研竞赛: ["科研", "竞赛", "比赛", "项目申报", "创新创业", "学术", "讲座", "论坛", "大讲堂"],
  行政公示: ["公示", "任职", "任免", "采购", "招标", "干部", "行政", "聘任"],
  生活服务: ["后勤", "食堂", "宿舍", "校园卡", "停水", "停电", "维修", "生活", "体检", "校车"],
};

export const TOPICS = Object.freeze(Object.keys(termsByTopic));
const day = 24 * 60 * 60 * 1000;

export function topicsFor(item) {
  const text = item.title + "\n" + item.body;
  return TOPICS.filter(topic => termsByTopic[topic].some(term => text.includes(term)));
}

export function rankNotice(item, {topics = []} = {}, now = Date.now()) {
  const matches = topicsFor(item).filter(topic => topics.includes(topic));
  let score = Math.min(40, 20 * matches.length);
  const age = now - Date.parse(item.publishedAt);
  if (age >= 0 && age <= day) score += 6;
  else if (age >= 0 && age <= 3 * day) score += 3;
  const reason = matches.length ? "你关注的主题：" + matches[matches.length - 1] : "";
  return {score, reason};
}
