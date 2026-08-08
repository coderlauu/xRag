package com.app.qa.tool;

import java.util.List;

/**
 * 一条参数复核失败。
 *
 * <p>{@code allowedValues} 只在枚举越界时非空——提示要给**允许值**而不是 Schema 原文
 * （PRD §12.7 / ui-spec §13.7.4）。把整段 Schema 抛给用户既没用，又暴露了内部契约形状。
 */
public record ToolArgumentViolation(String parameter, Kind kind, List<String> allowedValues) {

    public enum Kind {
        MISSING_REQUIRED,
        TYPE_MISMATCH,
        VALUE_NOT_ALLOWED
    }

    public ToolArgumentViolation {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public static ToolArgumentViolation missing(String parameter) {
        return new ToolArgumentViolation(parameter, Kind.MISSING_REQUIRED, List.of());
    }

    public static ToolArgumentViolation typeMismatch(String parameter) {
        return new ToolArgumentViolation(parameter, Kind.TYPE_MISMATCH, List.of());
    }

    public static ToolArgumentViolation notAllowed(String parameter, List<String> allowed) {
        return new ToolArgumentViolation(parameter, Kind.VALUE_NOT_ALLOWED, allowed);
    }
}
