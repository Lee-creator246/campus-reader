const storageKey = "campus-reader.safari.keyword-rules.1";
const emptyRules = () => ({blockedEnabled: false, blockedWords: [], priorityEnabled: false, priorityWords: []});
const asciiFold = text => text.replace(/[A-Z]/g, letter => letter.toLowerCase());

function cleanTerms(words) {
  if (!Array.isArray(words)) throw new Error("关键词格式有误");
  const distinct = new Map();
  for (const word of words) {
    if (typeof word !== "string" || /[\u0000-\u001f\u007f-\u009f]/u.test(word)) {
      throw new Error("关键词不能包含控制字符");
    }
    const term = word.replace(/^ +| +$/g, "");
    if (!term) continue;
    if ([...term].length > 100) throw new Error("每个关键词最多 100 个字");
    const folded = asciiFold(term);
    if (!distinct.has(folded)) distinct.set(folded, term);
    if (distinct.size > 50) throw new Error("每组最多保存 50 个关键词");
  }
  return [...distinct.values()];
}

export function parseKeywordTerms(text) {
  return cleanTerms(text.split(/[,，\r\n]+/));
}

function checkedRules(value) {
  if (!value || Object.keys(value).length !== 4 || typeof value.blockedEnabled !== "boolean" ||
      typeof value.priorityEnabled !== "boolean") throw new Error("规则格式有误");
  return {blockedEnabled: value.blockedEnabled, blockedWords: cleanTerms(value.blockedWords),
    priorityEnabled: value.priorityEnabled, priorityWords: cleanTerms(value.priorityWords)};
}

export function loadKeywordRules(storage) {
  try {
    const raw = (storage ?? globalThis.localStorage).getItem(storageKey);
    return raw === null ? emptyRules() : checkedRules(JSON.parse(raw));
  } catch {
    return {...emptyRules(), loadFailed: true};
  }
}

export function saveKeywordRules(value, storage) {
  const rules = checkedRules(value);
  if (rules.blockedEnabled && !rules.blockedWords.length) throw new Error("请填写屏蔽词，或关闭屏蔽词开关");
  if (rules.priorityEnabled && !rules.priorityWords.length) throw new Error("请填写关注词，或关闭关注词开关");
  if (loadKeywordRules(storage).loadFailed) throw new Error("本机规则无法读取，请重新读取或重置规则");
  (storage ?? globalThis.localStorage).setItem(storageKey, JSON.stringify(rules));
  return rules;
}

export function keywordMatch(item, rules) {
  const noMatch = {blocked: false, priority: false, score: 0, reason: ""};
  if (rules.loadFailed) return {...noMatch, blocked: true};
  if (!rules.blockedEnabled && !rules.priorityEnabled) return noMatch;
  const title = asciiFold(item.title);
  const text = title + "\n" + asciiFold(item.body.replace(/\u00a0/g, " "));
  if (rules.blockedEnabled && rules.blockedWords.some(word => text.includes(asciiFold(word)))) {
    return {...noMatch, blocked: true};
  }
  if (!rules.priorityEnabled) return noMatch;
  const titleWord = rules.priorityWords.find(word => title.includes(asciiFold(word)));
  if (titleWord) return {blocked: false, priority: true, score: 70, reason: "标题匹配关注词：" + titleWord};
  const bodyWord = rules.priorityWords.find(word => text.includes(asciiFold(word)));
  if (bodyWord) return {blocked: false, priority: true, score: 35, reason: "匹配关注词：" + bodyWord};
  return noMatch;
}

export function mountKeywordRules(container, initialRules, onChange) {
  const form = document.createElement("form");
  form.innerHTML = FORM_CONTENT;
  container.replaceChildren(form);
  const fields = form.elements;
  const status = form.querySelector('[role="status"]');
  let current = initialRules;
  function updateInputs() {
    fields.blockedWords.disabled = fields.blockedEnabled.disabled || !fields.blockedEnabled.checked;
    fields.priorityWords.disabled = fields.priorityEnabled.disabled || !fields.priorityEnabled.checked;
  }
  function display(rules, message) {
    current = rules;
    fields.blockedEnabled.checked = rules.blockedEnabled;
    fields.priorityEnabled.checked = rules.priorityEnabled;
    fields.blockedWords.value = rules.blockedWords.join("\n");
    fields.priorityWords.value = rules.priorityWords.join("\n");
    fields.saveRules.disabled = fields.blockedEnabled.disabled = fields.priorityEnabled.disabled = !!rules.loadFailed;
    fields.retryRules.hidden = !rules.loadFailed;
    updateInputs();
    status.textContent = rules.loadFailed ? "本机关键词规则无法读取，列表已暂停显示。可重新读取或重置规则。" : message;
  }
  function apply(rules, message) {
    display(rules, message);
    onChange(rules);
  }
  display(initialRules, "");
  form.addEventListener("change", updateInputs);
  form.addEventListener("submit", event => {
    event.preventDefault();
    if (current.loadFailed) return;
    try {
      const draft = {blockedEnabled: fields.blockedEnabled.checked, blockedWords: parseKeywordTerms(fields.blockedWords.value),
        priorityEnabled: fields.priorityEnabled.checked, priorityWords: parseKeywordTerms(fields.priorityWords.value)};
      apply(saveKeywordRules(draft), "关键词规则已保存。");
    } catch (error) {
      status.textContent = error instanceof DOMException ? "本机未能保存规则，请重试。" : error.message;
    }
  });
  fields.retryRules.addEventListener("click", () => apply(loadKeywordRules(), "已重新读取规则。"));
  fields.resetRules.addEventListener("click", () => {
    if (!window.confirm("清除本机的关注词和屏蔽词？")) return;
    try { globalThis.localStorage.removeItem(storageKey); }
    catch { status.textContent = "本机未能重置规则，请重试。"; return; }
    apply(emptyRules(), "关键词规则已重置。");
  });
  function handleStorage(event) {
    if (event.storageArea === globalThis.localStorage && (event.key === storageKey || event.key === null)) {
      apply(loadKeywordRules(), "规则已在另一个标签页更新。");
    }
  }
  window.addEventListener("storage", handleStorage);
  return () => window.removeEventListener("storage", handleStorage);
}

const FORM_CONTENT = [
  '<p>规则只在本浏览器内使用。标题或正文命中屏蔽词时隐藏通知，命中关注词的通知可在“重点关注”中查看。</p>',
  '<label><input type="checkbox" name="blockedEnabled"> 启用屏蔽词</label>',
  '<textarea name="blockedWords" aria-label="屏蔽词" rows="2" placeholder="每行一词"></textarea>',
  '<label><input type="checkbox" name="priorityEnabled"> 启用关注词</label>',
  '<textarea name="priorityWords" aria-label="关注词" rows="2" placeholder="每行一词"></textarea>',
  '<p>也可用中英文逗号分隔。每组最多 50 个词，每词最多 100 字。</p>',
  '<div class="actions"><button type="submit" class="action" name="saveRules">保存规则</button>',
  '<button type="button" class="text-button" name="resetRules">重置规则</button>',
  '<button type="button" class="action" name="retryRules" hidden>重新读取</button></div>',
  '<p role="status"></p>',
].join("");
