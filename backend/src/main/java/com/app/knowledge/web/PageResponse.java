package com.app.knowledge.web;

import java.util.List;

/**
 * 分页响应统一形状（api.md §1）：{@code {items, page, size, total}}。
 * {@code page} 从 1 开始。
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    /** {@code size} 的上限。请求超过这个值不报错，截到上限——分页参数越界不值得让请求失败。 */
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 20;

    public static int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    public static int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
