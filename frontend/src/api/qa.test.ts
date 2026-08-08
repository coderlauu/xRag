import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./errors";
import { listQaConversations, readIntentTree, toQaUserMessage } from "./qa";

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
});
