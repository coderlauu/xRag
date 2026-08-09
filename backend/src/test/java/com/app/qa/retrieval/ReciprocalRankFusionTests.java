package com.app.qa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.knowledge.search.ChunkSearchPort.ChunkMatch;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 3B-FUSE-01~07。 */
class ReciprocalRankFusionTests {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);

    private static ChannelResult channel(SearchChannelType type, long... chunkIds) {
        var matches = new java.util.ArrayList<ChunkMatch>();
        for (long chunkId : chunkIds) {
            matches.add(new ChunkMatch(chunkId, 1L, 1L, 0.0));
        }
        return new ChannelResult(type, matches);
    }

    @Test
    void fuse01_给定排名输出顺序确定且可复现() {
        var input = List.of(
                channel(SearchChannelType.INTENT_TARGETED, 10L, 20L, 30L),
                channel(SearchChannelType.KEYWORD, 30L, 40L));

        var first = fusion.fuse(input);
        var second = fusion.fuse(input);

        assertThat(first).extracting(FusedCandidate::chunkId).containsExactly(30L, 10L, 20L, 40L);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void fuse02_同一chunk跨通道只出现一次且分数累加() {
        var fused = fusion.fuse(List.of(
                channel(SearchChannelType.INTENT_TARGETED, 30L),
                channel(SearchChannelType.KEYWORD, 30L)));

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).hitChannels())
                .containsExactly(SearchChannelType.INTENT_TARGETED, SearchChannelType.KEYWORD);
        assertThat(fused.get(0).rrfScore()).isEqualTo(2.0 / 61);
    }

    @Test
    void fuse03_来源归属取priority最小的通道() {
        // 关键词先命中、定向后命中，归属仍是定向（priority 1 < 2）。
        var fused = fusion.fuse(List.of(
                channel(SearchChannelType.KEYWORD, 30L),
                channel(SearchChannelType.INTENT_TARGETED, 30L)));

        assertThat(fused.get(0).originChannel()).isEqualTo(SearchChannelType.INTENT_TARGETED);
    }

    @Test
    void fuse04_通道分数量纲变化不改变输出顺序() {
        // 同一组排名，把某个通道的 score 放大一万倍：融合只吃排名，结果必须逐字段相同。
        var normal = List.of(
                new ChannelResult(
                        SearchChannelType.KEYWORD,
                        List.of(new ChunkMatch(10L, 1L, 1L, 0.01), new ChunkMatch(20L, 1L, 1L, 0.001))),
                channel(SearchChannelType.GLOBAL_VECTOR, 20L));
        var inflated = List.of(
                new ChannelResult(
                        SearchChannelType.KEYWORD,
                        List.of(new ChunkMatch(10L, 1L, 1L, 100.0), new ChunkMatch(20L, 1L, 1L, 99.0))),
                channel(SearchChannelType.GLOBAL_VECTOR, 20L));

        assertThat(fusion.fuse(inflated)).isEqualTo(fusion.fuse(normal));
    }

    @Test
    void 完全并列时按chunkId升序稳定排序() {
        // 没有稳定 tie-break，"来源顺序即相关性顺序"这个承诺就成了随机数。
        var fused = fusion.fuse(List.of(channel(SearchChannelType.GLOBAL_VECTOR, 77L, 3L, 42L)));
        var again = fusion.fuse(List.of(channel(SearchChannelType.GLOBAL_VECTOR, 77L, 3L, 42L)));

        assertThat(fused).extracting(FusedCandidate::chunkId).containsExactly(77L, 3L, 42L);
        assertThat(again).isEqualTo(fused);

        var tied = fusion.fuse(List.of(
                channel(SearchChannelType.INTENT_TARGETED, 9L),
                channel(SearchChannelType.KEYWORD, 5L)));
        assertThat(tied).extracting(FusedCandidate::chunkId).containsExactly(5L, 9L);
    }

    @Test
    void fuse05_不存在基于文本哈希的去重兜底() throws Exception {
        // ADR 0008 决策 4：chunk 一定有主键，兜底路径只会带来静默丢证据的可能。
        var source = java.nio.file.Path.of(
                "src/main/java/com/app/qa/retrieval/ReciprocalRankFusion.java");
        assertThat(java.nio.file.Files.readString(source)).doesNotContain("hashCode");
    }

    @Test
    void fuse06_两段截断各自生效且不足时原样返回() {
        var fused = fusion.fuse(List.of(channel(SearchChannelType.GLOBAL_VECTOR, 1L, 2L, 3L, 4L, 5L)));

        assertThat(fusion.truncate(fused, 3)).hasSize(3);
        assertThat(fusion.truncate(fused, 10)).hasSize(5);
        assertThat(fusion.truncate(fused, 0)).isEmpty();
        assertThatThrownBy(() -> fusion.truncate(fused, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 空输入与空通道结果都产生空融合() {
        assertThat(fusion.fuse(List.of())).isEmpty();
        assertThat(fusion.fuse(List.of(ChannelResult.empty(SearchChannelType.KEYWORD)))).isEmpty();
    }

    @Test
    void 排名靠前的贡献更大且k值影响衰减速度() {
        var sharp = new ReciprocalRankFusion(1);
        var flat = new ReciprocalRankFusion(1000);
        var input = List.of(channel(SearchChannelType.GLOBAL_VECTOR, 1L, 2L));

        double sharpGap = sharp.fuse(input).get(0).rrfScore() - sharp.fuse(input).get(1).rrfScore();
        double flatGap = flat.fuse(input).get(0).rrfScore() - flat.fuse(input).get(1).rrfScore();

        assertThat(sharpGap).isGreaterThan(flatGap);
    }
}
