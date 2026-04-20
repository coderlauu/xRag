import test from "node:test";
import assert from "node:assert/strict";
import { classifyAskSessionPhase } from "./prod-ui-smoke.mjs";

test("classifyAskSessionPhase prefers explicit terminal states from the session panel", () => {
  assert.equal(classifyAskSessionPhase("会话 ID abc 已回答 全库"), "answered");
  assert.equal(classifyAskSessionPhase("会话 ID abc 需要收窄作用域 follow-up"), "needs_scope");
  assert.equal(classifyAskSessionPhase("会话 ID abc 已拒答"), "refused");
  assert.equal(classifyAskSessionPhase("会话 ID abc 失败 provider_timeout"), "failed");
});

test("classifyAskSessionPhase keeps active and stuck states non-terminal", () => {
  assert.equal(classifyAskSessionPhase("会话 ID abc 检索中"), "retrieving");
  assert.equal(classifyAskSessionPhase("会话 ID abc 生成中"), "synthesizing");
  assert.equal(classifyAskSessionPhase("会话 ID abc 待启动"), "idle");
  assert.equal(classifyAskSessionPhase("该会话长时间未进入终态 页面已停止自动轮询"), "stuck");
  assert.equal(classifyAskSessionPhase(""), null);
});
