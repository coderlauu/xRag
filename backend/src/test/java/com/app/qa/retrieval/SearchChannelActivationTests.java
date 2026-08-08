package com.app.qa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.knowledge.search.ChunkSearchPort;
import com.app.qa.retrieval.channel.GlobalVectorChannel;
import com.app.qa.retrieval.channel.IntentTargetedChannel;
import com.app.qa.retrieval.channel.KeywordChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 3B-CH-01~07。 */
class SearchChannelActivationTests {

    /** 可观测的假端口：记录每次调用，用来断言"没被调用过"这类否定命题。 */
    private static final class RecordingPort implements ChunkSearchPort {
        private final List<String> calls = new ArrayList<>();

        @Override
        public List<ChunkMatch> searchByVector(Set<Long> kbIds, float[] queryVector, int limit) {
            calls.add("vector" + new java.util.TreeSet<>(kbIds));
            return List.of(new ChunkMatch(1L, 1L, 1L, -0.1));
        }

        @Override
        public List<ChunkMatch> searchByKeyword(Set<Long> kbIds, String rawQuery, int limit) {
            calls.add("keyword" + new java.util.TreeSet<>(kbIds) + ":" + rawQuery);
            return List.of(new ChunkMatch(2L, 1L, 1L, 0.5));
        }
    }

    private final RecordingPort port = new RecordingPort();
    private final IntentTargetedChannel targeted =
            new IntentTargetedChannel(port, question -> new float[] {0.1f});
    private final GlobalVectorChannel global =
            new GlobalVectorChannel(port, question -> new float[] {0.1f});
    private final KeywordChannel keyword = new KeywordChannel(port);

    @Test
    void ch01_有定向动作时定向通道激活且限定在动作里的知识库() {
        var scope = new RetrievalScope("退货需要哪些材料？", Set.of(12L, 31L), false);

        assertThat(targeted.isActive(scope)).isTrue();
        targeted.search(scope, 50);
        assertThat(port.calls).singleElement().asString().contains("12").contains("31");
    }

    @Test
    void ch02_有兜底动作时全局通道激活且不带kb过滤() {
        var scope = new RetrievalScope("年会报销标准", Set.of(), true);

        assertThat(global.isActive(scope)).isTrue();
        global.search(scope, 50);
        assertThat(port.calls).containsExactly("vector[]");
    }

    @Test
    void ch03_定向存在时全局通道不执行任何查询() {
        // 定向 ⊂ 全局，同时激活等于查两遍同一份数据（ADR 0008 决策 2）。
        var scope = new RetrievalScope("退货政策", Set.of(12L), true);

        assertThat(targeted.isActive(scope)).isTrue();
        assertThat(global.isActive(scope)).isFalse();
        assertThat(port.calls).isEmpty();
    }

    @Test
    void ch04_关键词通道始终激活且作用域随向量通道() {
        var targetedScope = new RetrievalScope("退货政策", Set.of(12L), false);
        var globalScope = new RetrievalScope("退货政策", Set.of(), true);

        assertThat(keyword.isActive(targetedScope)).isTrue();
        assertThat(keyword.isActive(globalScope)).isTrue();

        keyword.search(targetedScope, 50);
        keyword.search(globalScope, 50);
        assertThat(port.calls.get(0)).contains("[12]");
        assertThat(port.calls.get(1)).contains("[]");
    }

    @Test
    void ch05_检索用改写后的问题而不是用户原话() {
        var scope = new RetrievalScope("海外仓入库超过时效后如何处理？", Set.of(), true);
        keyword.search(scope, 50);
        assertThat(port.calls).singleElement().asString().endsWith("海外仓入库超过时效后如何处理？");
    }

    @Test
    void ch07_三个通道各有唯一priority且定向最优先() {
        assertThat(SearchChannelType.INTENT_TARGETED.priority()).isEqualTo(1);
        assertThat(SearchChannelType.KEYWORD.priority()).isEqualTo(2);
        assertThat(SearchChannelType.GLOBAL_VECTOR.priority()).isEqualTo(3);
        // 只有一套优先级，也没有预留走不到的枚举值。
        assertThat(SearchChannelType.values()).hasSize(3);
    }

    @Test
    void 通道结果保持端口给出的顺序不再自行排序() {
        // 融合只消费排名，所以通道这一层任何"顺手再排一次"都会改变融合结果。
        var scope = new RetrievalScope("退货", Set.of(), true);
        assertThat(global.search(scope, 50).matches())
                .extracting(ChunkSearchPort.ChunkMatch::chunkId)
                .containsExactly(1L);
    }

    @Test
    void 空问题不能构造检索范围() {
        assertThat(java.util.stream.Stream.of("", "   ")
                        .allMatch(blank -> {
                            try {
                                new RetrievalScope(blank, Set.of(), true);
                                return false;
                            } catch (IllegalArgumentException expected) {
                                return true;
                            }
                        }))
                .isTrue();
    }
}
