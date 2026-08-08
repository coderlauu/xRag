import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppRoutes } from "./App";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("Phase 3A 前端基础路由", () => {
  it("根路径仍进入知识库且顶级导航按批准顺序出现", async () => {
    renderAt("/");

    expect(await screen.findByRole("heading", { name: "知识库" })).toBeInTheDocument();
    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual([
      "问答",
      "知识库",
      "意图树",
      "诊断",
    ]);
  });

  it("问答与意图树都进入已批准的正式页面骨架", async () => {
    const emptyTree = {
      revision: { revisionId: null, revisionNo: 0, updatedAt: "2026-08-08T10:00:00+08:00" },
      root: { nodeId: "00000000-0000-0000-0000-000000000001", name: "全部意图", virtual: true, editable: false },
      nodes: [],
      summary: { nodeCount: 0, enabledLeafCount: 0, incompleteLeafCount: 0 },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) =>
        Promise.resolve(
          new Response(
            JSON.stringify(
              String(input).includes("/intent-tree") && !String(input).includes("knowledge-bases")
                ? emptyTree
                : { items: [], nextCursor: null },
            ),
            { status: 200 },
          ),
        ),
      ),
    );

    const chat = renderAt("/chat");
    expect(screen.getByRole("heading", { name: "问答" })).toBeInTheDocument();
    expect(screen.getByText("从你的知识库开始提问")).toBeInTheDocument();
    chat.unmount();

    renderAt("/intent-tree");
    expect(screen.getByRole("heading", { name: "意图树" })).toBeInTheDocument();
    expect(
      await screen.findByText(/只有启用的叶子参与问题匹配/),
    ).toBeInTheDocument();
  });
});
