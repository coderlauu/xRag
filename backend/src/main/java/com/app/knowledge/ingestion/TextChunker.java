package com.app.knowledge.ingestion;

import com.app.knowledge.model.ChunkConfig;
import com.app.knowledge.model.TextChunk;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把一整篇文本切成分块。纯本地字符串处理——没有数据库、没有网络、没有 IO，
 * 因此可以被单元测试完整覆盖。
 *
 * <p>见 tech/knowledge-base/architecture.md §5。
 */
@Component
public class TextChunker {

    public List<TextChunk> chunk(String text, ChunkConfig config) {
        // 空白文档切出 0 个分块是**正常结果**，不是错误：扫描版 PDF、空文件都会走到这里。
        // ui-spec.md 为它专门设计了一种空态（revision>0 却没有分块 = "处理过但切不出内容"），
        // 与"还没处理"区分开。
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> pieces = switch (config.strategy()) {
            case FIXED_SIZE -> fixedSize(text, config);
            case RECURSIVE -> recursive(text, config);
        };

        List<TextChunk> chunks = new ArrayList<>(pieces.size());
        for (String piece : pieces) {
            chunks.add(new TextChunk(chunks.size(), piece, piece.length(),
                    TokenEstimator.estimate(piece), ContentHash.sha256(piece)));
        }
        return chunks;
    }

    /**
     * 按字符数硬切，相邻分块重叠 {@code overlap} 个字符。
     *
     * <p>**不做 trim**：这个策略的价值就是行为完全可预测（第 n 块一定是
     * {@code [n*step, n*step+chunkSize)}），修剪空白会破坏这个性质。需要干净边界的
     * 场景该选 {@code RECURSIVE}。
     */
    private List<String> fixedSize(String text, ChunkConfig config) {
        int step = config.chunkSize() - config.overlap();   // ChunkConfig 已保证 step >= 1
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + config.chunkSize(), text.length());
            pieces.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return pieces;
    }

    /** 先按分隔符优先级递归切成不超长的片段，再把相邻片段合并回接近 chunkSize 的分块。 */
    private List<String> recursive(String text, ChunkConfig config) {
        List<String> fragments = splitToFragments(text, config.separators(), config.chunkSize());
        return merge(fragments, config.chunkSize(), config.overlap());
    }

    /**
     * 递归拆分，保证每个返回的片段都不超过 {@code chunkSize}。
     *
     * <p>分隔符**保留在前一个片段的末尾**，这样把片段依次拼起来能还原原文，切点也就
     * 必然落在分隔符边界上而不是词中间。
     */
    private List<String> splitToFragments(String text, List<String> separators, int chunkSize) {
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= chunkSize) {
            return List.of(text);
        }

        for (int i = 0; i < separators.size(); i++) {
            String separator = separators.get(i);
            if (separator.isEmpty() || !text.contains(separator)) {
                continue;
            }
            List<String> remaining = separators.subList(i + 1, separators.size());
            List<String> fragments = new ArrayList<>();
            for (String piece : splitKeepingSeparator(text, separator)) {
                if (piece.length() <= chunkSize) {
                    fragments.add(piece);
                } else {
                    fragments.addAll(splitToFragments(piece, remaining, chunkSize));
                }
            }
            return fragments;
        }

        // 所有分隔符都用尽仍然超长（一整段没有空格的 base64、连续的中文长句等）。
        // 必须硬切——否则要么返回一个远超 chunkSize 的分块，要么在递归里打转。
        return hardSplit(text, chunkSize);
    }

    private List<String> splitKeepingSeparator(String text, String separator) {
        List<String> pieces = new ArrayList<>();
        int from = 0;
        int at;
        while ((at = text.indexOf(separator, from)) >= 0) {
            pieces.add(text.substring(from, at + separator.length()));
            from = at + separator.length();
        }
        if (from < text.length()) {
            pieces.add(text.substring(from));
        }
        return pieces;
    }

    private List<String> hardSplit(String text, int chunkSize) {
        List<String> pieces = new ArrayList<>();
        for (int start = 0; start < text.length(); start += chunkSize) {
            pieces.add(text.substring(start, Math.min(start + chunkSize, text.length())));
        }
        return pieces;
    }

    /**
     * 把片段贪心地装进分块，装不下就收口，并从末尾回退若干片段作为下一块的重叠部分。
     *
     * <p>重叠以**整片段**为单位回退（而不是按字符数截断），否则重叠部分会从句子中间开始，
     * 恰好毁掉这个策略想要的边界性质。
     */
    private List<String> merge(List<String> fragments, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLength = 0;

        for (String fragment : fragments) {
            if (currentLength + fragment.length() > chunkSize && !current.isEmpty()) {
                addIfNotBlank(chunks, String.join("", current));
                // 回退条件有两个：尾巴要缩到 overlap 以内，且必须给下一个片段腾出位置。
                // 只写前者的话，"保留的重叠 + 新片段"仍可能超过 chunkSize——上限被突破时
                // 优先保住"分块不超长"这条硬约束，重叠只是锦上添花。每个片段都 <=
                // chunkSize，所以最坏情况退到空也一定装得下，循环不会空转。
                while (!current.isEmpty()
                        && (currentLength > overlap || currentLength + fragment.length() > chunkSize)) {
                    currentLength -= current.remove(0).length();
                }
            }
            current.add(fragment);
            currentLength += fragment.length();
        }
        if (!current.isEmpty()) {
            addIfNotBlank(chunks, String.join("", current));
        }
        return chunks;
    }

    /**
     * 分块内容修剪首尾空白后再落库：分隔符是连着前一个片段带过来的，不修剪的话每个分块
     * 都会以 {@code \n\n} 结尾，展示和编辑时都碍事。修剪后变成纯空白的片段直接丢弃。
     */
    private void addIfNotBlank(List<String> chunks, String content) {
        String trimmed = content.strip();
        if (!trimmed.isEmpty()) {
            chunks.add(trimmed);
        }
    }
}
