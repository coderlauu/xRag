package com.app.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.knowledge.model.ChunkConfig;
import com.app.knowledge.model.ChunkStrategy;
import com.app.knowledge.model.TextChunk;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TextChunkerTests {

    private final TextChunker chunker = new TextChunker();

    private List<String> contents(List<TextChunk> chunks) {
        return chunks.stream().map(TextChunk::content).toList();
    }

    @Nested
    class 两种策略共同的边界 {

        /** 空文档切出 0 块是正常结果，不是错误——ui-spec 为它设计了专门的空态。 */
        @ParameterizedTest
        @EnumSource(ChunkStrategy.class)
        void 空文档返回零个分块(ChunkStrategy strategy) {
            assertThat(chunker.chunk("", ChunkConfig.of(strategy, 10, 2))).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(ChunkStrategy.class)
        void 纯空白文档返回零个分块(ChunkStrategy strategy) {
            assertThat(chunker.chunk("   \n\n\t  \n ", ChunkConfig.of(strategy, 10, 2))).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(ChunkStrategy.class)
        void 单字符文档返回一个分块(ChunkStrategy strategy) {
            List<TextChunk> chunks = chunker.chunk("a", ChunkConfig.of(strategy, 10, 2));

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).content()).isEqualTo("a");
            assertThat(chunks.get(0).charCount()).isEqualTo(1);
            assertThat(chunks.get(0).index()).isZero();
        }

        @ParameterizedTest
        @EnumSource(ChunkStrategy.class)
        void 序号从零开始连续递增(ChunkStrategy strategy) {
            List<TextChunk> chunks = chunker.chunk("x".repeat(95), ChunkConfig.of(strategy, 10, 0));

            assertThat(chunks).hasSizeGreaterThan(1);
            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).index()).isEqualTo(i);
            }
        }

        /** "abc" 的 SHA-256，用一个公开可查的定值锁住算法，防止实现被换成别的摘要。 */
        @ParameterizedTest
        @EnumSource(ChunkStrategy.class)
        void contentHash是内容的SHA256(ChunkStrategy strategy) {
            List<TextChunk> chunks = chunker.chunk("abc", ChunkConfig.of(strategy, 100, 0));

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).contentHash())
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        }
    }

    @Nested
    class 固定长度策略 {

        private List<TextChunk> chunk(String text, int chunkSize, int overlap) {
            return chunker.chunk(text, ChunkConfig.of(ChunkStrategy.FIXED_SIZE, chunkSize, overlap));
        }

        @Test
        void 长度恰好等于chunkSize时只切出一块() {
            List<TextChunk> chunks = chunk("a".repeat(10), 10, 2);

            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).charCount()).isEqualTo(10);
        }

        /** 只多一个字符就必须切成两块，且第二块要带上 overlap 的尾巴。 */
        @Test
        void 长度等于chunkSize加一时切出两块() {
            List<TextChunk> chunks = chunk("0123456789A", 10, 2);

            assertThat(contents(chunks)).containsExactly("0123456789", "89A");
        }

        @Test
        void overlap为零时分块拼接还原原文() {
            String text = "0123456789ABCDEFGHIJKLMN";

            List<TextChunk> chunks = chunk(text, 10, 0);

            assertThat(contents(chunks)).containsExactly("0123456789", "ABCDEFGHIJ", "KLMN");
            assertThat(String.join("", contents(chunks))).isEqualTo(text);
        }

        /** overlap = chunkSize - 1 是允许的极值：步进退化为 1，块数 = len - chunkSize + 1。 */
        @Test
        void overlap为chunkSize减一时步进为一个字符() {
            List<TextChunk> chunks = chunk("0123456789", 5, 4);

            assertThat(contents(chunks))
                    .containsExactly("01234", "12345", "23456", "34567", "45678", "56789");
        }

        /** 硬切不修剪空白：这个策略的价值就是行为完全可预测。 */
        @Test
        void 不修剪空白() {
            List<TextChunk> chunks = chunk("  ab  cd  ", 5, 0);

            assertThat(contents(chunks)).containsExactly("  ab ", " cd  ");
        }
    }

    @Nested
    class 递归策略 {

        private List<TextChunk> chunk(String text, int chunkSize, int overlap) {
            return chunker.chunk(text, ChunkConfig.of(ChunkStrategy.RECURSIVE, chunkSize, overlap));
        }

        /**
         * 这条是递归策略存在的全部理由：切点必须落在段落边界上。
         * 三段各 6 字，加上段落分隔符后每段片段 8 字，chunkSize=10 装不下两段。
         */
        @Test
        void 切点落在段落边界而不是段落中间() {
            String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";

            List<TextChunk> chunks = chunk(text, 10, 0);

            assertThat(contents(chunks)).containsExactly("第一段内容。", "第二段内容。", "第三段内容。");
            assertThat(contents(chunks)).noneMatch(content -> content.contains("\n"));
        }

        /** 段落装不下时降到下一级分隔符（换行），仍然不从行中间切。 */
        @Test
        void 段落超长时降级到换行分隔符() {
            String text = "第一行内容\n第二行内容\n第三行内容";

            List<TextChunk> chunks = chunk(text, 8, 0);

            assertThat(contents(chunks)).containsExactly("第一行内容", "第二行内容", "第三行内容");
        }

        /**
         * 一整串没有任何分隔符的字符——递归到最后必须硬切。写错的话要么返回一个远超
         * chunkSize 的分块，要么在递归里打转不返回。
         */
        @Test
        void 没有任何分隔符的超长串被硬切开() {
            String text = "a".repeat(25);

            List<TextChunk> chunks = chunk(text, 10, 0);

            assertThat(contents(chunks)).containsExactly("a".repeat(10), "a".repeat(10), "a".repeat(5));
            assertThat(String.join("", contents(chunks))).isEqualTo(text);
        }

        @Test
        void 分块长度都不超过chunkSize() {
            String text = ("段落一，包含若干中文句子。它还有第二句。\n\n"
                    + "Paragraph two with English words and spaces.\n\n"
                    + "b".repeat(120)).repeat(3);

            List<TextChunk> chunks = chunk(text, 50, 10);

            assertThat(chunks).isNotEmpty();
            assertThat(chunks).allSatisfy(c -> assertThat(c.charCount()).isLessThanOrEqualTo(50));
        }

        /** 片段足够小时重叠才生效：以整片段为单位回退，重叠不会从句子中间开始。 */
        @Test
        void 重叠以整片段为单位回退() {
            String text = "one two three four five six";

            List<TextChunk> chunks = chunk(text, 12, 8);

            assertThat(chunks).hasSizeGreaterThan(1);
            // 相邻两块必有共同的词，且每块都是完整的词序列（不会出现半个词）
            for (int i = 1; i < chunks.size(); i++) {
                List<String> previous = List.of(chunks.get(i - 1).content().split(" "));
                List<String> current = List.of(chunks.get(i).content().split(" "));
                assertThat(current).as("第 %d 块应与上一块有重叠词", i).containsAnyElementsOf(previous);
            }
            assertThat(contents(chunks)).allSatisfy(
                    content -> assertThat(text).contains(content));
        }
    }

    @Nested
    class 参数校验 {

        @Test
        void overlap等于chunkSize被拒绝() {
            assertThatThrownBy(() -> ChunkConfig.of(ChunkStrategy.FIXED_SIZE, 10, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overlap 必须小于 chunkSize");
        }

        @Test
        void overlap大于chunkSize被拒绝() {
            assertThatThrownBy(() -> ChunkConfig.of(ChunkStrategy.RECURSIVE, 10, 11))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overlap 必须小于 chunkSize");
        }

        @Test
        void chunkSize非正数被拒绝() {
            assertThatThrownBy(() -> ChunkConfig.of(ChunkStrategy.FIXED_SIZE, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("chunkSize 必须为正数");
        }

        @Test
        void overlap为负数被拒绝() {
            assertThatThrownBy(() -> ChunkConfig.of(ChunkStrategy.FIXED_SIZE, 10, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overlap 不能为负数");
        }
    }
}
