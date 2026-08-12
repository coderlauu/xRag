import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./errors";
import { listQaConversations, readIntentTree, streamQaQuestion, toQaUserMessage } from "./qa";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("QA API client", () => {
  it("使用独立的/api/qa前缀而不误走/api/v1", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      items: [],
      nextCursor: null,
    }), { status: 200 })));
    vi.stubGlobal("fetch", fetchMock);

    await listQaConversations({ limit: 20 });
    await readIntentTree();

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/qa/conversations?limit=20");
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/qa/intent-tree");
  });

  it("版本冲突保留结构化扩展且未知错误只展示安全通用文案", () => {
    const conflict = ApiError.fromResponse(409, {
      code: "INTENT_TREE_REVISION_CONFLICT",
      detail: "配置已被更新，请刷新后比较再保存。",
      currentRevisionId: "revision-9",
    });
    expect(conflict.extensions.currentRevisionId).toBe("revision-9");
    expect(toQaUserMessage(conflict)).toEqual({
      title: "意图树配置已被更新。",
      detail: "本地输入会保留，请刷新比较后再保存。",
    });

    const unknown = ApiError.fromResponse(500, {
      code: "INTERNAL_VENDOR_DETAIL",
      detail: "不应展示的内部信息",
    });
    expect(toQaUserMessage(unknown)).toEqual({
      title: "操作失败，请稍后重试。",
    });
  });

  it("用fetch解析跨分块SSE并在唯一终态后忽略事件", async () => {
    const encoder = new TextEncoder();
    const payload = [
      `event:accepted\nid:1\ndata:{"sequence":1,"conversationId":"c1","turnId":"t1","taskId":"g1","userMessageId":"m1"}\n\n`,
      `:keep-alive\n\nevent:answer_delta\nid:2\ndata:{"sequence":2,"text":"纯文本"}\n\n`,
      `event:completed\nid:3\ndata:{"sequence":3,"assistantMessageId":"a1","saved":true}\n\n`,
      `event:answer_delta\nid:4\ndata:{"sequence":4,"text":"不应出现"}\n\n`,
    ].join("");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(payload.slice(0, 37)));
        controller.enqueue(encoder.encode(payload.slice(37)));
        controller.close();
      },
    }), { headers: { "content-type": "text/event-stream" } })));
    const events: Array<{ event: string }> = [];

    await streamQaQuestion(
      { clientRequestId: "r1", conversationId: null, text: "问题" },
      (event) => events.push(event),
    );

    expect(events.map((event) => event.event)).toEqual(["accepted", "answer_delta", "completed"]);
    expect(fetch).toHaveBeenCalledWith("/api/qa/turns:stream", expect.objectContaining({
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    }));
  });
});
