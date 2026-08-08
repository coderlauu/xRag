import { useEffect, useState } from "react";
import { Modal } from "../../components/Modal";
import {
  previewIntentImpact,
  toQaUserMessage,
  type IntentImpact,
  type IntentNodeType,
  type IntentTreeNode,
  type IntentTreeSnapshot,
  type TreeOperation,
} from "../../api/qa";
import type { UserFacingMessage } from "../../api/errors";

const OPERATION_TITLE: Record<TreeOperation, string> = {
  DISABLE_SUBTREE: "停用",
  DELETE_SUBTREE: "删除",
  ENABLE_CONFIGURED_SUBTREE: "启用",
};

const OPERATION_CONFIRM: Record<TreeOperation, string> = {
  DISABLE_SUBTREE: "停用整个子树",
  DELETE_SUBTREE: "删除整个子树",
  ENABLE_CONFIGURED_SUBTREE: "启用整个子树",
};

/**
 * 危险命令的影响预览（ui-spec §9.2、§10）。
 *
 * 先预览再执行不是礼貌，是必要条件：父节点的停用/删除一律作用于整棵子树，用户必须先看见
 * "会动几个节点、其中几个是当前可匹配的叶子"。预览不锁 revision，命令仍带同一个 expected，
 * 树在这中间变了就让服务端 409，由界面重新预览。
 */
export function ImpactDialog({
  node,
  operation,
  expectedRevisionId,
  busy,
  commandError,
  onCancel,
  onConfirm,
}: {
  node: IntentTreeNode;
  operation: TreeOperation;
  expectedRevisionId: string | null;
  busy: boolean;
  commandError: UserFacingMessage | null;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const [impact, setImpact] = useState<IntentImpact | null>(null);
  const [error, setError] = useState<UserFacingMessage | null>(null);

  useEffect(() => {
    let cancelled = false;
    previewIntentImpact(node.nodeId, expectedRevisionId, operation)
      .then((preview) => {
        if (!cancelled) setImpact(preview);
      })
      .catch((cause: unknown) => {
        if (!cancelled) setError(toQaUserMessage(cause));
      });
    return () => {
      cancelled = true;
    };
  }, [node.nodeId, expectedRevisionId, operation]);

  const blocked = (impact?.blockingNodes.length ?? 0) > 0;

  return (
    <Modal
      title={`${OPERATION_TITLE[operation]}「${node.name}」及其子树？`}
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={busy}>
            取消
          </button>
          <button
            className={operation === "ENABLE_CONFIGURED_SUBTREE" ? "primary" : "danger"}
            type="button"
            disabled={busy || impact === null || blocked}
            onClick={onConfirm}
          >
            {busy ? "处理中…" : OPERATION_CONFIRM[operation]}
          </button>
        </>
      }
    >
      {impact === null && !error ? <p role="status">正在计算影响范围…</p> : null}
      {impact ? (
        <>
          <p>
            将影响 <strong>{impact.nodeCount}</strong> 个节点，其中{" "}
            <strong>{impact.enabledLeafCount}</strong> 个是当前可匹配的叶子，涉及{" "}
            <strong>{impact.boundKnowledgeBaseCount}</strong> 个绑定知识库。
          </p>
          <p className="muted">已有对话和历史记录不会被删除。</p>
          {blocked ? (
            <p className="inline-error" role="alert">
              <strong>子树里有 {impact.blockingNodes.length} 个节点阻塞了这次操作。</strong>
              <span> 请先补全它们的配置；服务端不会跳过坏节点后报告整体成功。</span>
            </p>
          ) : null}
        </>
      ) : null}
      {error ? (
        <p className="inline-error" role="alert">
          <strong>{error.title}</strong>
          {error.detail ? <span> {error.detail}</span> : null}
        </p>
      ) : null}
      {commandError ? (
        <p className="inline-error" role="alert">
          <strong>{commandError.title}</strong>
          {commandError.detail ? <span> {commandError.detail}</span> : null}
        </p>
      ) : null}
    </Modal>
  );
}

/**
 * 新建节点（ui-spec §8）。只收名称、角色和运营说明：新节点强制停用，其余字段回检查器
 * 慢慢补，避免在一个弹层里同时处理"建结构"和"配路由"两件事。
 */
export function CreateNodeDialog({
  snapshot,
  parentId,
  busy,
  error,
  onCancel,
  onCreate,
}: {
  snapshot: IntentTreeSnapshot;
  parentId: string;
  busy: boolean;
  error: UserFacingMessage | null;
  onCancel: () => void;
  onCreate: (type: IntentNodeType, name: string, description: string) => void;
}) {
  const parent = snapshot.nodes.find((node) => node.nodeId === parentId);
  const atRoot = parent === undefined;
  const [type, setType] = useState<IntentNodeType>("GROUP");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  return (
    <Modal
      title="新增节点"
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel} disabled={busy}>
            取消
          </button>
          <button
            className="primary"
            type="button"
            disabled={busy || !name.trim()}
            onClick={() => onCreate(atRoot ? "GROUP" : type, name.trim(), description)}
          >
            {busy ? "创建中…" : "创建节点"}
          </button>
        </>
      }
    >
      <p>将在「{parent?.fullPath ?? snapshot.root.name}」下创建。</p>
      {atRoot ? (
        <p className="field-hint">根下只允许业务域（组织节点）。</p>
      ) : (
        <label>
          <span>节点角色</span>
          <select
            value={type}
            aria-label="节点角色"
            onChange={(event) => setType(event.target.value as IntentNodeType)}
          >
            <option value="GROUP">组织</option>
            <option value="KB">KB 叶子</option>
            <option value="SYSTEM">SYSTEM 叶子</option>
          </select>
        </label>
      )}
      <label>
        <span>名称</span>
        <input
          value={name}
          maxLength={80}
          aria-label="新节点名称"
          onChange={(event) => setName(event.target.value)}
        />
      </label>
      <label>
        <span>运营说明</span>
        <textarea
          value={description}
          rows={3}
          maxLength={1000}
          aria-label="新节点说明"
          onChange={(event) => setDescription(event.target.value)}
        />
      </label>
      <p className="field-hint">新节点强制停用，配置完整后再显式启用。</p>
      {error ? (
        <p className="inline-error" role="alert">
          <strong>{error.title}</strong>
          {error.detail ? <span> {error.detail}</span> : null}
        </p>
      ) : null}
    </Modal>
  );
}

/** 未保存修改的离开确认（ui-spec §7.5）。默认焦点不在破坏性按钮上。 */
export function DirtyGuardDialog({
  onStay,
  onDiscard,
}: {
  onStay: () => void;
  onDiscard: () => void;
}) {
  return (
    <Modal
      title="有未保存的修改"
      onClose={onStay}
      footer={
        <>
          <button className="primary" type="button" onClick={onStay}>
            留在此处
          </button>
          <button className="danger" type="button" onClick={onDiscard}>
            放弃修改
          </button>
        </>
      }
    >
      <p>当前节点的修改还没有保存。离开会丢弃这些输入。</p>
    </Modal>
  );
}
