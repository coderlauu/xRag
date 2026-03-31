export type LogLevel = "debug" | "info" | "warn" | "error";

export interface Logger {
  debug(message: string, meta?: Record<string, unknown>): void;
  info(message: string, meta?: Record<string, unknown>): void;
  warn(message: string, meta?: Record<string, unknown>): void;
  error(message: string, meta?: Record<string, unknown>): void;
}

function log(level: LogLevel, message: string, meta: Record<string, unknown> = {}) {
  const payload = {
    timestamp: new Date().toISOString(),
    level,
    service: "xrag-worker",
    message,
    ...meta
  };

  const serialized = JSON.stringify(payload);
  if (level === "error") {
    console.error(serialized);
    return;
  }

  if (level === "warn") {
    console.warn(serialized);
    return;
  }

  console.log(serialized);
}

export function createLogger(minLevel: LogLevel): Logger {
  const levelOrder: Record<LogLevel, number> = {
    debug: 10,
    info: 20,
    warn: 30,
    error: 40
  };

  const shouldLog = (level: LogLevel) => levelOrder[level] >= levelOrder[minLevel];

  return {
    debug(message, meta) {
      if (shouldLog("debug")) log("debug", message, meta);
    },
    info(message, meta) {
      if (shouldLog("info")) log("info", message, meta);
    },
    warn(message, meta) {
      if (shouldLog("warn")) log("warn", message, meta);
    },
    error(message, meta) {
      if (shouldLog("error")) log("error", message, meta);
    }
  };
}
