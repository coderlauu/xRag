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

  useEffect(() => {
    const dialog = ref.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);

  return (
    <dialog ref={ref} className="modal" onCancel={onClose} onClose={onClose}>
      <h2>{title}</h2>
      <div className="modal-body">{children}</div>
      <div className="modal-footer">{footer}</div>
    </dialog>
  );
}
