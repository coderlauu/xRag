import { useEffect, useRef, type ReactNode } from "react";

interface ModalProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer: ReactNode;
}

/**
 * 共用弹层。用原生 {@code <dialog>} 而不是自己造遮罩：焦点陷阱、Esc 关闭、
 * 遮罩层叠都是浏览器给的，自己实现容易漏掉键盘可达性。
 */
export function Modal({ title, onClose, children, footer }: ModalProps) {
  const ref = useRef<HTMLDialogElement>(null);

  /**
   * **这个 effect 不能有 cleanup 去 `close()`，别加回来。**
   *
   * `dialog.close()` 会派发原生 `close` 事件，而下面把它接到了 `onClose` 上——
   * 也就是"关闭弹层"这个动作会回调父组件的 `setDialog(null)`。在 `StrictMode`
   * 下（`main.tsx` 开着）开发模式会**故意双调用 effect**：挂载 → 立刻 cleanup →
   * 再挂载。于是序列变成
   *
   *   showModal() → cleanup 的 close() → 派发 close 事件 → onClose()
   *   → 父组件 setDialog(null) → Modal 当场卸载
   *
   * 弹层在一帧内开了又没。症状是**所有弹层类操作看起来"点击毫无反应"，且控制台
   * 没有任何报错**——`2026-07-28` 就是这样耗掉一轮排查的：DOM 里查不到 `<dialog>`、
   * onClick 明明绑定着、React 也确实挂载了。
   *
   * 卸载时不需要手动 `close()`：React 会移除整个 `<dialog>` 节点，浏览器随之
   * 退出 top layer。而 `!dialog.open` 守卫是必需的——StrictMode 第二次运行 effect
   * 时弹层已经是打开的，对已打开的 dialog 再调 `showModal()` 会抛 `InvalidStateError`。
   *
   * 生产构建里 StrictMode 不做双调用，所以原写法"碰巧"不出问题。**它本来就是个
   * 真实的副作用缺陷，只是被 StrictMode 提前暴露了**，不是 StrictMode 的锅。
   */
  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }, []);

  return (
    <dialog ref={ref} className="modal" onCancel={onClose} onClose={onClose}>
      <h2>{title}</h2>
      <div className="modal-body">{children}</div>
      <div className="modal-footer">{footer}</div>
    </dialog>
  );
}
