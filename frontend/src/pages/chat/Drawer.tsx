import { useEffect, useRef, type ReactNode } from "react";

interface DrawerProps {
  title: string;
  /** 从哪一侧滑出。对话列表在左、本轮处理在右，与原型一致（ui-spec §5.3）。 */
  side?: "left" | "right";
  onClose: () => void;
  children: ReactNode;
}

/**
 * 对话列表与本轮处理的覆盖层（ui-spec §2.2 / §10）。
 *
 * 选 C 之后它在**所有断点**上都是唯一形态，不再只是窄屏降级；≤720px 时铺满整宽。
 *
 * 用原生 `<dialog>` + `showModal()` 而不是自己画遮罩：焦点约束、背景不可交互和层叠都是
 * 浏览器给的。挂载 effect **不能加 cleanup 去 `close()`**，理由与 `components/Modal.tsx`
 * 里那段注释完全相同（StrictMode 双调用会让抽屉开了又没，且不报错）。
 *
 * Escape 同时接 `onCancel` 和 `onKeyDown`：前者是浏览器的原生行为，后者是因为 jsdom
 * 不实现 UA 的 Esc→cancel，测试里必须有一条能走通的路径。两条都只是 `onClose()`，重复
 * 触发无副作用。
 */
export function Drawer({ title, side = "right", onClose, children }: DrawerProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
    closeButton.current?.focus();
  }, []);

  return (
    <dialog
      ref={ref}
      className={`qa-drawer qa-drawer-${side}`}
      aria-label={title}
      onCancel={onClose}
      onClose={onClose}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          onClose();
        }
      }}
    >
      <div className="qa-drawer-head">
        <h2>{title}</h2>
        <button ref={closeButton} type="button" onClick={onClose}>
          关闭
        </button>
      </div>
      <div className="qa-drawer-body">{children}</div>
    </dialog>
  );
}
