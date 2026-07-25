package com.app.knowledge.model;

import java.util.List;

/**
 * 分块参数。见 tech/knowledge-base/architecture.md §5。
 *
 * @param strategy   分块策略
 * @param chunkSize  单个分块的字符数上限
 * @param overlap    相邻分块的重叠字符数，必须小于 {@code chunkSize}
 * @param separators 仅 {@code RECURSIVE} 使用，按优先级从高到低
 */
public record ChunkConfig(ChunkStrategy strategy, int chunkSize, int overlap, List<String> separators) {

    /** 从段落到句子再到词的降级顺序。中英文标点都在，因为文档常常混排。 */
    public static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "\n", "。", ". ", " ");

    public static final int DEFAULT_CHUNK_SIZE = 1000;
    public static final int DEFAULT_OVERLAP = 100;

    public ChunkConfig {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须为正数，实际 " + chunkSize);
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数，实际 " + overlap);
        }
        // overlap >= chunkSize 时每个窗口的步进 <= 0：固定长度切分会原地打转停不下来，
        // 递归策略的合并阶段会不断把同一批片段重新塞回去。这不是"效果不好"，是不终止，
        // 所以在构造时就挡掉而不是留给算法自己发现。
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap 必须小于 chunkSize，实际 overlap=%d chunkSize=%d".formatted(overlap, chunkSize));
        }
        separators = List.copyOf(separators);
    }

    public static ChunkConfig defaults() {
        return new ChunkConfig(ChunkStrategy.RECURSIVE, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP, DEFAULT_SEPARATORS);
    }

    public static ChunkConfig of(ChunkStrategy strategy, int chunkSize, int overlap) {
        return new ChunkConfig(strategy, chunkSize, overlap, DEFAULT_SEPARATORS);
    }
}
