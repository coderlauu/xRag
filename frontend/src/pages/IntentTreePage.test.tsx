import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { IntentTreePage } from "./IntentTreePage";
import { setTestViewport } from "../test/viewport";

/**
 * 意图树 C — Route Canvas 的组件用例，对应 3A-UI-09~16。
 *
 * mock 边界同样是全局 `fetch`：本工单要证明"每条写命令都带了 expectedRevisionId、危险命令
 * 先发了 impact、成功后用返回的整套 nodes 原子替换"，这些只有在真实请求上断言才算数。
 */

const REVISION_A = "aaaaaaaa-0000-0000-0000-000000000001";
const REVISION_B = "bbbbbbbb-0000-0000-0000-000000000002";
const ROOT_ID = "00000000-0000-0000-0000-000000000001";
const GROUP_ID = "cc111111-0000-0000-0000-000000000001";
const KB_LEAF_ID = "dd222222-0000-0000-0000-000000000002";
const OTHER_LEAF_ID = "ee333333-0000-0000-0000-000000000003";

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function problem(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ status, code, detail: "服务端说明", ...extra }), {
    status,
    headers: { "content-type": "application/problem+json" },
  });
}

const group = {
  nodeId: GROUP_ID,
  parentId: ROOT_ID,
  depth: 1,
  sortOrder: 0,
  type: "GROUP",
  name: "售后服务",
  description: "运营说明",
  examples: [],
  knowledgeBaseId: null,
  systemAction: null,
  promptSnippet: null,
  enabled: true,
  effectiveEnabled: true,
  fullPath: "售后服务",
  childCount: 2,
  enabledLeafCount: 1,
  versionState: "CURRENT",
};

const kbLeaf = {
  nodeId: KB_LEAF_ID,
  parentId: GROUP_ID,
  depth: 2,
  sortOrder: 0,
  type: "KB",
  name: "退货政策",
  description: "关于退货材料与时限的问题。",
  examples: ["退货需要哪些材料？"],
  knowledgeBaseId: 12,
  systemAction: null,
  promptSnippet: null,
  enabled: true,
  effectiveEnabled: true,
  fullPath: "售后服务 > 退货政策",
  childCount: 0,
  enabledLeafCount: 1,
  versionState: "CURRENT",
};

const otherLeaf = {
  ...kbLeaf,
  nodeId: OTHER_LEAF_ID,
  sortOrder: 1,
  name: "清关流程",
  description: null,
  examples: [],
  knowledgeBaseId: null,
  enabled: false,
  effectiveEnabled: false,
  fullPath: "售后服务 > 清关流程",
  enabledLeafCount: 0,
};

const snapshot = {
  revision: { revisionId: REVISION_A, revisionNo: 8, updatedAt: "2026-08-08T10:00:00+08:00" },
  root: { nodeId: ROOT_ID, name: "全部意图", virtual: true, editable: false },
  nodes: [group, kbLeaf, otherLeaf],
  summary: { nodeCount: 3, enabledLeafCount: 1, incompleteLeafCount: 1 },
};

const knowledgeBases = {
  items: [
    { knowledgeBaseId: 12, name: "售后政策", enabled: true, chunkCount: 83 },
    { knowledgeBaseId: 15, name: "跨境物流", enabled: true, chunkCount: 41 },
  ],
  nextCursor: null,
};

/** 命令成功后服务端回的整棵新树：叶子改名并进入新 revision。 */
const renamedMutation = {
  revision: { revisionId: REVISION_B, revisionNo: 9, updatedAt: "2026-08-08T11:00:00+08:00" },
  changedNodeId: KB_LEAF_ID,
  treeSummary: { nodeCount: 3, enabledLeafCount: 1, incompleteLeafCount: 1 },
  nodes: [
    group,
    { ...kbLeaf, name: "退货与退款政策", fullPath: "售后服务 > 退货与退款政策" },
    otherLeaf,
  ],
};

interface Recorded {
  url: string;
  method: string;
  body: Record<string, unknown> | undefined;
}

interface Routes {
  tree: () => Response;
  bindable: () => Response;
  impact: () => Response;
  command: (url: string, body: Record<string, unknown> | undefined) => Response;
}

const defaultRoutes: Routes = {
  tree: () => json(snapshot),
  bindable: () => json(knowledgeBases),
  impact: () =>
    json({
      revisionId: REVISION_A,
      operation: "DISABLE_SUBTREE",
      nodeCount: 3,
      enabledLeafCount: 1,
      boundKnowledgeBaseCount: 1,
      blockingNodes: [],
    }),
  command: () => json(renamedMutation),
};

function mockApi(overrides: Partial<Routes> = {}) {
  const routes = { ...defaultRoutes, ...overrides };
  const calls: Recorded[] = [];

  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : undefined;
    calls.push({ url, method, body });

    if (url.includes("/bindable-knowledge-bases")) return Promise.resolve(routes.bindable());
    if (url.includes("/impact")) return Promise.resolve(routes.impact());
    if (method === "GET") return Promise.resolve(routes.tree());
    return Promise.resolve(routes.command(url, body));
  });

  vi.stubGlobal("fetch", fetchMock);
  return {
    calls,
    of: (method: string, fragment: string) =>
      calls.filter((call) => call.method === method && call.url.includes(fragment)),
  };
}

beforeEach(() => {
  setTestViewport(1440, 900);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function selectLeaf(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("treeitem", { name: /退货政策 · KB · 已启用/ }));
  return screen.getByRole("complementary", { name: "节点检查器" });
}

describe("意图树 C — 画布与检查器", () => {
  it("加载完整快照后按角色与状态标签展示节点，选中后检查器显示完整路径", async () => {
    mockApi();
    const user = userEvent.setup();
    render(<IntentTreePage />);

    expect(await screen.findByRole("treeitem", { name: /售后服务 · 组织 · 已启用/ })).toBeInTheDocument();
    expect(screen.getByRole("treeitem", { name: /清关流程 · KB · 已停用/ })).toBeInTheDocument();
    expect(screen.getByText(/当前显示最新配置 · 版本 8/)).toBeInTheDocument();

    const inspector = await selectLeaf(user);
    expect(within(inspector).getByText("售后服务 > 退货政策")).toBeInTheDocument();
    expect(within(inspector).getByLabelText("节点名称")).toHaveValue("退货政策");
    expect(within(inspector).getByLabelText("正向示例 1")).toHaveValue("退货需要哪些材料？");
    expect(within(inspector).getByRole("radio", { name: /售后政策/ })).toBeChecked();
  });

  it("保存节点带 expectedRevisionId，成功后用返回的完整快照原子替换", async () => {
    const api = mockApi();
    const user = userEvent.setup();
    render(<IntentTreePage />);
    const inspector = await selectLeaf(user);

    const name = within(inspector).getByLabelText("节点名称");
    await user.clear(name);
    await user.type(name, "退货与退款政策");
    expect(screen.getByText("有未保存修改")).toBeInTheDocument();

    await user.click(within(inspector).getByRole("button", { name: "保存节点" }));

    await waitFor(() => expect(api.of("PUT", `/nodes/${KB_LEAF_ID}`)).toHaveLength(1));
    expect(api.of("PUT", `/nodes/${KB_LEAF_ID}`)[0]?.body).toMatchObject({
      expectedRevisionId: REVISION_A,
      name: "退货与退款政策",
      examples: ["退货需要哪些材料？"],
      knowledgeBaseId: 12,
    });
    expect(await screen.findByText(/当前显示最新配置 · 版本 9/)).toBeInTheDocument();
    expect(
      screen.getByRole("treeitem", { name: /退货与退款政策 · KB · 已启用/ }),
    ).toBeInTheDocument();
  });

  it("revision 冲突保留本地表单并刷新出服务端当前版本供比较", async () => {
    mockApi({
      command: () =>
        problem(409, "INTENT_TREE_REVISION_CONFLICT", {
          currentRevisionId: REVISION_B,
          currentRevisionNo: 9,
        }),
    });
    const user = userEvent.setup();
    render(<IntentTreePage />);
    const inspector = await selectLeaf(user);

    const name = within(inspector).getByLabelText("节点名称");
    await user.clear(name);
    await user.type(name, "本地未保存的名字");
    await user.click(within(inspector).getByRole("button", { name: "保存节点" }));

    expect(await screen.findByText(/该节点已被更新（服务端当前版本 9）/)).toBeInTheDocument();
    expect(screen.getByText("意图树配置已被更新。")).toBeInTheDocument();
    expect(within(inspector).getByLabelText("节点名称")).toHaveValue("本地未保存的名字");
    // 冲突后不自动覆盖任何一边：画布仍是服务端的名字，表单仍是用户的输入。
    expect(screen.getByRole("treeitem", { name: /退货政策 · KB · 已启用/ })).toBeInTheDocument();
  });

  it("有未保存修改时切换节点先要确认，放弃后才换节点", async () => {
    mockApi();
    const user = userEvent.setup();
    render(<IntentTreePage />);
    const inspector = await selectLeaf(user);

    await user.type(within(inspector).getByLabelText("节点名称"), "改了");
    await user.click(screen.getByRole("treeitem", { name: /清关流程/ }));

    expect(await screen.findByText("有未保存的修改")).toBeInTheDocument();
    expect(screen.getByLabelText("节点名称")).toHaveValue("退货政策改了");

    await user.click(screen.getByRole("button", { name: "放弃修改" }));
    await waitFor(() => expect(screen.getByLabelText("节点名称")).toHaveValue("清关流程"));
  });

  it("按角色给出不同表单，且不出现 3A 明确不做的能力", async () => {
    const systemLeaf = {
      ...otherLeaf,
      nodeId: "99999999-0000-0000-0000-000000000009",
      type: "SYSTEM",
      name: "打招呼",
      description: "用户寒暄。",
      examples: ["你好"],
      knowledgeBaseId: null,
      systemAction: "GREETING",
      fullPath: "售后服务 > 打招呼",
    };
    mockApi({ tree: () => json({ ...snapshot, nodes: [group, systemLeaf] }) });
    const user = userEvent.setup();
    render(<IntentTreePage />);

    // 组织节点：只有匹配描述，没有示例、绑定和回答附加规则。
    await user.click(await screen.findByRole("treeitem", { name: /售后服务/ }));
    const inspector = screen.getByRole("complementary", { name: "节点检查器" });
    expect(within(inspector).getByLabelText("匹配描述")).toBeInTheDocument();
    expect(within(inspector).queryByLabelText("正向示例 1")).not.toBeInTheDocument();
    expect(within(inspector).queryByLabelText("回答附加规则")).not.toBeInTheDocument();

    // SYSTEM 叶子：受控闭集下拉，没有知识库选择器。
    await user.click(screen.getByRole("treeitem", { name: /打招呼/ }));
    expect(within(inspector).getByLabelText("系统场景")).toHaveValue("GREETING");
    expect(within(inspector).queryByLabelText("搜索知识库")).not.toBeInTheDocument();
    expect(within(inspector).getByLabelText("回答附加规则")).toBeInTheDocument();
    expect(screen.queryByText(/MCP/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /回滚|导入|导出|优化整棵树|换父/ })).not.toBeInTheDocument();
  });

  it("绑定的知识库已失效时给出阻塞提示而不是静默换绑", async () => {
    mockApi({
      tree: () => json({ ...snapshot, nodes: [group, { ...kbLeaf, knowledgeBaseId: 99 }] }),
    });
    const user = userEvent.setup();
    render(<IntentTreePage />);

    await user.click(await screen.findByRole("treeitem", { name: /退货政策/ }));
    const inspector = screen.getByRole("complementary", { name: "节点检查器" });
    expect(
      within(inspector).getByText("绑定的知识库不可用，请重新选择后再启用。"),
    ).toBeInTheDocument();
    expect(within(inspector).queryByRole("radio", { checked: true })).not.toBeInTheDocument();
  });
});

describe("意图树 C — 危险命令与启停", () => {
  it("从节点菜单删除父节点同样先预览整个子树", async () => {
    const api = mockApi({
      impact: () =>
        json({
          revisionId: REVISION_A,
          operation: "DELETE_SUBTREE",
          nodeCount: 3,
          enabledLeafCount: 1,
          boundKnowledgeBaseCount: 1,
          blockingNodes: [],
        }),
    });
    const user = userEvent.setup();
    render(<IntentTreePage />);

    const groupItem = await screen.findByRole("treeitem", { name: /售后服务/ });
    await user.click(within(groupItem).getAllByRole("button", { name: "节点操作" })[0]!);
    await user.click(
      within(screen.getByRole("group", { name: "售后服务 的操作" })).getByRole("button", {
        name: "删除整个子树",
      }),
    );

    await waitFor(() => expect(api.of("POST", "/impact")).toHaveLength(1));
    expect(api.of("POST", "/impact")[0]?.body).toEqual({
      expectedRevisionId: REVISION_A,
      operation: "DELETE_SUBTREE",
    });

    await user.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "删除整个子树" }),
    );
    await waitFor(() => expect(api.of("POST", "/delete")).toHaveLength(1));
    expect(api.of("POST", "/delete")[0]?.body).toEqual({
      expectedRevisionId: REVISION_A,
      scope: "SUBTREE",
    });
  });

  it("停用先预览影响范围再执行，命令仍带同一个 expectedRevisionId", async () => {
    const api = mockApi();
    const user = userEvent.setup();
    render(<IntentTreePage />);
    const inspector = await selectLeaf(user);

    await user.click(within(inspector).getByRole("button", { name: "停用整个子树" }));

    await waitFor(() => expect(api.of("POST", "/impact")).toHaveLength(1));
    expect(api.of("POST", "/impact")[0]?.body).toEqual({
      expectedRevisionId: REVISION_A,
      operation: "DISABLE_SUBTREE",
    });
    expect(await screen.findByText(/将影响/)).toBeInTheDocument();

    // 检查器与确认弹层里的主操作同名，必须把点击限定在弹层内。
    await user.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "停用整个子树" }),
    );
    await waitFor(() => expect(api.of("POST", "/disable")).toHaveLength(1));
    expect(api.of("POST", "/disable")[0]?.body).toEqual({
      expectedRevisionId: REVISION_A,
      scope: "SUBTREE",
    });
  });

  it("启用组织节点区分仅当前与整个已配置完整的子树，后者先预览且阻塞时不放行", async () => {
    const api = mockApi({
      impact: () =>
        json({
          revisionId: REVISION_A,
          operation: "ENABLE_CONFIGURED_SUBTREE",
          nodeCount: 2,
          enabledLeafCount: 0,
          boundKnowledgeBaseCount: 0,
          blockingNodes: [OTHER_LEAF_ID],
        }),
    });
    const user = userEvent.setup();
    render(<IntentTreePage />);

    await user.click(await screen.findByRole("treeitem", { name: /清关流程/ }));
    const inspector = screen.getByRole("complementary", { name: "节点检查器" });
    await user.click(within(inspector).getByRole("button", { name: "仅启用当前节点" }));

    await waitFor(() => expect(api.of("POST", "/enable")).toHaveLength(1));
    expect(api.of("POST", "/enable")[0]?.body).toEqual({
      expectedRevisionId: REVISION_A,
      scope: "NODE_ONLY",
    });
    // 叶子不提供 CONFIGURED_SUBTREE，避免用户以为能一次带起整棵子树。
    expect(
      within(inspector).queryByRole("button", { name: "启用整个已配置完整的子树" }),
    ).not.toBeInTheDocument();
  });

  it("启用整个子树遇到阻塞节点时列出阻塞并禁用主操作", async () => {
    mockApi({
      tree: () => json({ ...snapshot, nodes: [{ ...group, enabled: false, effectiveEnabled: false }, kbLeaf, otherLeaf] }),
      impact: () =>
        json({
          revisionId: REVISION_A,
          operation: "ENABLE_CONFIGURED_SUBTREE",
          nodeCount: 3,
          enabledLeafCount: 0,
          boundKnowledgeBaseCount: 1,
          blockingNodes: [OTHER_LEAF_ID],
        }),
    });
    const user = userEvent.setup();
    render(<IntentTreePage />);

    await user.click(await screen.findByRole("treeitem", { name: /售后服务 · 组织 · 已停用/ }));
    const inspector = screen.getByRole("complementary", { name: "节点检查器" });
    await user.click(within(inspector).getByRole("button", { name: "启用整个已配置完整的子树" }));

    expect(await screen.findByText(/子树里有 1 个节点阻塞了这次操作/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "启用整个子树" })).toBeDisabled();
  });

  it("新建节点强制停用并在创建后选中它继续完善", async () => {
    const created = {
      ...renamedMutation,
      changedNodeId: "ff444444-0000-0000-0000-000000000004",
      nodes: [
        group,
        kbLeaf,
        otherLeaf,
        {
          ...otherLeaf,
          nodeId: "ff444444-0000-0000-0000-000000000004",
          name: "清关材料",
          fullPath: "售后服务 > 清关材料",
        },
      ],
    };
    const api = mockApi({ command: () => json(created, 201) });
    const user = userEvent.setup();
    render(<IntentTreePage />);

    await screen.findByRole("treeitem", { name: /售后服务/ });
    await user.click(screen.getByRole("button", { name: "新增根节点" }));
    expect(screen.getByText(/新节点强制停用/)).toBeInTheDocument();

    await user.type(screen.getByLabelText("新节点名称"), "清关材料");
    await user.click(screen.getByRole("button", { name: "创建节点" }));

    await waitFor(() => expect(api.of("POST", "/intent-tree/nodes")).toHaveLength(1));
    expect(api.of("POST", "/intent-tree/nodes")[0]?.body).toMatchObject({
      expectedRevisionId: REVISION_A,
      parentId: ROOT_ID,
      type: "GROUP",
      name: "清关材料",
    });
    await waitFor(() => expect(screen.getByLabelText("节点名称")).toHaveValue("清关材料"));
  });
});

describe("意图树 C — 搜索、键盘与响应式", () => {
  it("搜索保留祖先路径，无结果时提供清除搜索", async () => {
    mockApi();
    const user = userEvent.setup();
    render(<IntentTreePage />);
    await screen.findByRole("treeitem", { name: /售后服务/ });

    await user.type(screen.getByLabelText("搜索节点"), "退货");
    await waitFor(() =>
      expect(screen.queryByRole("treeitem", { name: /清关流程/ })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole("treeitem", { name: /售后服务/ })).toBeInTheDocument();
    expect(screen.getByRole("treeitem", { name: /退货政策/ })).toBeInTheDocument();

    await user.clear(screen.getByLabelText("搜索节点"));
    await user.type(screen.getByLabelText("搜索节点"), "不存在的关键词");
    expect(await screen.findByText(/没有匹配「不存在的关键词」的节点/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "清除搜索" }));
    expect(await screen.findByRole("treeitem", { name: /清关流程/ })).toBeInTheDocument();
  });

  it("键盘可以在树里移动、选中节点并打开节点菜单", async () => {
    mockApi();
    const user = userEvent.setup();
    render(<IntentTreePage />);
    const first = await screen.findByRole("treeitem", { name: /售后服务/ });

    first.focus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("treeitem", { name: /退货政策/ })).toHaveFocus();

    await user.keyboard("{Enter}");
    await waitFor(() => expect(screen.getByLabelText("节点名称")).toHaveValue("退货政策"));

    await user.click(
      within(screen.getByRole("treeitem", { name: /退货政策/ })).getByRole("button", {
        name: "节点操作",
      }),
    );
    const menu = screen.getByRole("group", { name: "退货政策 的操作" });
    expect(within(menu).getByRole("button", { name: "删除整个子树" })).toBeInTheDocument();

    await user.click(within(menu).getByRole("button", { name: "在检查器中编辑" }));
    expect(screen.getByRole("heading", { level: 2, name: "节点检查器" })).toHaveFocus();
  });

  it("画布可缩放且缩放不影响键盘选中", async () => {
    mockApi();
    const user = userEvent.setup();
    render(<IntentTreePage />);
    await screen.findByRole("treeitem", { name: /售后服务/ });

    expect(screen.getByText("100%")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "放大" }));
    expect(screen.getByText("115%")).toBeInTheDocument();

    screen.getByRole("treeitem", { name: /售后服务/ }).focus();
    await user.keyboard("{ArrowDown}{Enter}");
    await waitFor(() => expect(screen.getByLabelText("节点名称")).toHaveValue("退货政策"));

    await user.click(screen.getByRole("button", { name: "重置缩放" }));
    expect(screen.getByText("100%")).toBeInTheDocument();
  });

  it("375px 下画布与检查器变成列表→详情，返回后节点仍在", async () => {
    setTestViewport(375, 812);
    mockApi();
    const user = userEvent.setup();
    render(<IntentTreePage />);

    await user.click(await screen.findByRole("treeitem", { name: /退货政策/ }));
    expect(screen.queryByRole("treeitem", { name: /退货政策/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText("节点名称")).toHaveValue("退货政策");

    await user.click(screen.getByRole("button", { name: "返回节点列表" }));
    expect(await screen.findByRole("treeitem", { name: /退货政策/ })).toBeInTheDocument();
  });
});
