import { useEffect } from "react";

interface ToastProps {
  message: string;
  onDismiss: () => void;
}

/**
 * 一次操作结果的短暂反馈。
 *
 * <p>用 toast 而不是页面内的提示条，是因为这些消息说的是"刚才那一下发生了什么"
 * （ui-spec §6：保存后要区分"向量已更新"与"内容未变化，未重新计算向量"），
 * 看过就没有价值了，留在页面上反而会和下一次操作的结果混淆。
 *
 * <p>{@code role="status"} 让屏幕阅读器以礼貌方式播报——这类消息不该打断用户当前的操作。
 */
export function Toast({ message, onDismiss }: ToastProps) {
  useEffect(() => {
    const timer = setTimeout(onDismiss, 4000);
    return () => clearTimeout(timer);
  }, [message, onDismiss]);

  return (
    <div className="toast" role="status">
      {message}
    </div>
  );
}
