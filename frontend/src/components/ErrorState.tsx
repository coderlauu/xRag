import { toUserMessage } from "../api/errors";

interface ErrorStateProps {
  error: unknown;
  /** 传了才渲染重试按钮——不是所有错误都值得原地重试（404 就不该）。 */
  onRetry?: () => void;
}

/**
 * 全站统一的错误态。页面把捕获到的异常原样传进来即可，**不要自己判断错误类型**——
 * 错误码到用户文案的映射只存在于 api/errors.ts 一处（ui-spec §7），散到各页面就会
 * 出现同一个 429 在不同页面说法不同。
 */
export function ErrorState({ error, onRetry }: ErrorStateProps) {
  const { title, detail } = toUserMessage(error);
  return (
    <div className="state-block state-error" role="alert">
      <p className="state-title">{title}</p>
      {detail ? <p className="state-detail">{detail}</p> : null}
      {onRetry ? (
        <button type="button" onClick={onRetry}>
          重试
        </button>
      ) : null}
    </div>
  );
}
