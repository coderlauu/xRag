import http from "node:http";

const tasks = new Map();
const json = (response, status, body) => {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  response.end(body == null ? "" : JSON.stringify(body));
};
const id = (prefix, n) => `${prefix}0000000-0000-4000-8000-${String(n).padStart(12, "0")}`.slice(-36);
const frame = (response, event, sequence, data = {}) => response.write(
  `event:${event}\nid:${sequence}\ndata:${JSON.stringify({ sequence, ...data })}\n\n`,
);

function turn(task) {
  const active = task.status === "QUEUED" || task.status === "GENERATING";
  const saved = task.saved && !active;
  return {
    turn: {
      turnId: task.turnId,
      userMessageId: task.userMessageId,
      status: task.status,
      terminalReason: task.reason,
      startedAt: new Date().toISOString(),
      completedAt: active ? null : new Date().toISOString(),
      degradations: [],
      intentRevisionId: null,
    },
    inspector: null,
    assistantMessage: saved ? {
      messageId: task.answerId,
      turnId: task.turnId,
      sequenceNo: 2,
      role: "ASSISTANT",
      type: "ASSISTANT_ANSWER",
      content: task.answer,
      incomplete: task.status === "STOPPED" || task.status === "FAILED",
      createdAt: new Date().toISOString(),
    } : null,
    clarification: null,
    retrieval: null,
    tools: [],
    generation: {
      taskId: task.taskId,
      status: task.status,
      queuedOnce: task.queued,
      answerSaved: active ? null : task.saved,
      assistantMessageId: saved ? task.answerId : null,
    },
    retryAfterMs: active ? 150 : undefined,
  };
}

let sequence = 0;
const server = http.createServer((request, response) => {
  const url = new URL(request.url, "http://127.0.0.1:3002");
  response.setHeader("access-control-allow-origin", "*");
  response.setHeader("access-control-allow-headers", "content-type, accept");
  response.setHeader("access-control-allow-methods", "GET, POST, OPTIONS");
  if (request.method === "OPTIONS") {
    response.writeHead(204);
    return response.end();
  }
  if (request.method === "GET" && url.pathname === "/api/qa/conversations") {
    return json(response, 200, { items: [], nextCursor: null });
  }
  if (request.method === "POST" && url.pathname === "/api/qa/turns:stream") {
    let body = "";
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      const input = JSON.parse(body);
      const n = ++sequence;
      const task = {
        conversationId: id("c", n), turnId: id("t", n), taskId: id("g", n),
        userMessageId: id("u", n), answerId: id("a", n), question: input.message.text,
        answer: "这是逐步产生的纯文本答案。\n第二行保持原样。", status: "GENERATING",
        reason: null, saved: true, queued: false, response,
      };
      tasks.set(task.turnId, task);
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache",
        connection: "keep-alive",
      });
      frame(response, "accepted", 1, {
        conversationId: task.conversationId,
        turnId: task.turnId,
        taskId: task.taskId,
        userMessageId: task.userMessageId,
      });
      if (task.question.includes("排队取消")) {
        task.status = "QUEUED"; task.queued = true;
        return frame(response, "queued", 2);
      }
      if (task.question.includes("排队完成")) {
        task.status = "QUEUED"; task.queued = true; frame(response, "queued", 2);
        setTimeout(() => { task.status = "GENERATING"; frame(response, "generation_started", 3, { queuedOnce: true }); }, 250);
        setTimeout(() => frame(response, "answer_delta", 4, { text: task.answer }), 500);
        return setTimeout(() => { task.status = "COMPLETED"; task.reason = "COMPLETED"; frame(response, "completed", 5, { assistantMessageId: task.answerId, saved: true }); response.end(); }, 800);
      }
      frame(response, "generation_started", 2, { queuedOnce: false });
      if (task.question.includes("断连恢复")) {
        setTimeout(() => {
          response.destroy();
          setTimeout(() => { task.status = "COMPLETED"; task.reason = "COMPLETED"; }, 450);
        }, 120);
        return;
      }
      if (task.question.includes("系统失败")) {
        task.status = "FAILED"; task.reason = "MODEL_UNAVAILABLE"; task.saved = false;
        frame(response, "failed", 3, { code: "MODEL_UNAVAILABLE", retryable: true, assistantMessageId: null, saved: false });
        return response.end();
      }
      if (task.question.includes("等待超时")) {
        task.status = "TIMED_OUT"; task.reason = "QUEUE_WAIT_TIMEOUT"; task.saved = false; task.queued = true;
        frame(response, "timed_out", 3, { retryable: true });
        return response.end();
      }
      frame(response, "answer_delta", 3, { text: task.answer });
      if (task.question.includes("未保存")) {
        task.status = "COMPLETED"; task.reason = "COMPLETED"; task.saved = false;
        frame(response, "completed", 4, { assistantMessageId: null, saved: false });
        return response.end();
      }
      if (task.question.includes("生成停止")) return;
      task.status = "COMPLETED"; task.reason = "COMPLETED";
      frame(response, "completed", 4, { assistantMessageId: task.answerId, saved: true });
      response.end();
    });
    return;
  }
  const stop = url.pathname.match(/^\/api\/qa\/generation-tasks\/([^/]+)\/stop$/);
  if (request.method === "POST" && stop) {
    const task = [...tasks.values()].find((candidate) => candidate.taskId === stop[1]);
    if (!task) return json(response, 404, { code: "GENERATION_TASK_NOT_FOUND" });
    task.status = "STOPPED"; task.reason = "STOP_REQUESTED";
    task.saved = Boolean(task.answer && !task.question.includes("排队取消"));
    frame(task.response, "stopped", task.question.includes("排队取消") ? 3 : 4, {
      assistantMessageId: task.saved ? task.answerId : null,
      saved: task.saved,
      hasPartialAnswer: task.saved,
    });
    task.response.end();
    return json(response, 200, { status: "STOPPED", alreadyTerminal: false });
  }
  const turnMatch = url.pathname.match(/^\/api\/qa\/conversations\/([^/]+)\/turns\/([^/]+)$/);
  if (request.method === "GET" && turnMatch) {
    const task = tasks.get(turnMatch[2]);
    return task ? json(response, 200, turn(task)) : json(response, 404, { code: "TURN_NOT_FOUND" });
  }
  const messages = url.pathname.match(/^\/api\/qa\/conversations\/([^/]+)\/messages$/);
  if (request.method === "GET" && messages) {
    const task = [...tasks.values()].find((candidate) => candidate.conversationId === messages[1]);
    const items = task ? [{
      messageId: task.userMessageId, turnId: task.turnId, sequenceNo: 1, role: "USER",
      type: "USER_QUERY", content: task.question, createdAt: new Date().toISOString(),
    }, ...(task.saved && !["QUEUED", "GENERATING"].includes(task.status) ? [turn(task).assistantMessage] : [])] : [];
    return json(response, 200, { items, previousCursor: null });
  }
  const detail = url.pathname.match(/^\/api\/qa\/conversations\/([^/]+)$/);
  if (request.method === "GET" && detail) {
    const task = [...tasks.values()].find((candidate) => candidate.conversationId === detail[1]);
    return task ? json(response, 200, {
      conversationId: task.conversationId, title: task.question, version: 0,
      lastActiveAt: new Date().toISOString(),
      latestTurn: { turnId: task.turnId, status: task.status, completedAt: null },
    }) : json(response, 404, { code: "CONVERSATION_NOT_FOUND" });
  }
  json(response, 404, { code: "NOT_FOUND", path: url.pathname });
});

server.listen(3002, "127.0.0.1", () => console.log("3C browser fixture listening on 3002"));
