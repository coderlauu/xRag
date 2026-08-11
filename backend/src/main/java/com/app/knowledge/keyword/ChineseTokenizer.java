package com.app.knowledge.keyword;

import com.huaban.analysis.jieba.JiebaSegmenter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 关键词通道的唯一切词入口（ADR 0008 决策 3）。
 *
 * <p><b>为什么只有一个 Bean</b>：索引期与查询期必须切出一致的 token，否则入库时写的是
 * 「退货 政策」、查询时切成「退 货政策」，关键词通道会安静地什么都召回不到——没有报错，
 * 只是召回率悄悄归零。让两边共用同一个实例，把这条"长期纪律"变成结构性事实：
 * 问答模块传原始查询串给 {@code ChunkSearchPort}，切词永远发生在这里。
 *
 * <p><b>换词典 = 重建 {@code document_chunk_keyword} 全表</b>。词典变了，历史行里的 token
 * 就与新查询切法不再匹配，而这同样没有任何报错。{@link #dictionaryFingerprint()} 存在的理由
 * 就是让"某次召回异常是不是换词典造成的"这个问题可以事后回答。
 */
@Component
public class ChineseTokenizer {

    /**
     * 最小停用词表。
     *
     * <p><b>起草时刻意不做停用词表</b>，理由是"没有证据支撑一份中文停用词表的取舍"。
     * 实现工单 08 时证据出现了：查询改用 OR 之后，单个高频虚词会让关键词通道匹配几乎所有文档
     * ——任何含"的"的查询命中任何含"的"的文档，通道等于噪声源。
     *
     * <p>所以这份表只收**最高频的虚词**，不收任何实词：判据是"它出现在文档里几乎不携带检索
     * 意义"。索引期与查询期共用同一份表，一致性不受影响。要扩它需要同样量级的证据，
     * 不是"看着像停用词"。
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "也", "就", "都", "而", "及", "或",
            "有", "对", "把", "被", "从", "到", "为", "以", "于", "之", "着", "过",
            "吗", "呢", "吧", "啊", "我", "你", "他", "她", "它", "这", "那", "个");

    /** 分词器本身线程安全（内部只读词典），一个实例够用。 */
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /**
     * 切词并拼成 {@code to_tsvector('simple', ...)} 的输入。
     *
     * <p>用 {@code 'simple'} 配置而不是某种语言配置：文本进库前已经切好词，不需要 Postgres
     * 再做词干还原或停用词处理——它也做不了中文。
     */
    public String toTsvectorInput(String text) {
        return String.join(" ", tokens(text));
    }

    /**
     * 切词并拼成 {@code to_tsquery('simple', ...)} 的输入，词之间取 <b>OR</b>。
     *
     * <p><b>为什么是 OR 不是 AND</b>：这是实现工单 08 时被集成测试逼出来的更正。用 AND 的话，
     * "退货需要哪些材料"要求文档同时含有全部四个词，而真实文档往往只命中其中两三个——
     * 关键词通道会几乎从不触发，第三条通道等于白建。OR 让它回到"词项召回"的本分，
     * 排序交给 {@code ts_rank_cd}（命中越多、越密集越靠前），最终顺序再由 RRF 决定。
     *
     * <p>召回变宽的代价由 RRF 与截断承接：只命中一个常见词的文档排名很低，进不了最终 topN。
     *
     * <p>返回 {@code null} 表示这个查询切不出任何可检索的 token（例如纯标点）。调用方必须把它
     * 当成"关键词通道无结果"，而不是拼一个空 {@code tsquery} 给数据库——后者会直接抛错。
     */
    public String toTsqueryInput(String query) {
        List<String> tokens = tokens(query);
        return tokens.isEmpty() ? null : String.join(" | ", tokens);
    }

    /**
     * 保序去重的 token 列表。
     *
     * <p>去掉四类：空白、单个非中日韩字符（"a""1" 这类噪声）、tsquery 语法字符，
     * 以及 {@link #STOP_WORDS} 里的高频虚词。
     */
    public List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var unique = new LinkedHashSet<String>();
        for (var word : segmenter.process(text, JiebaSegmenter.SegMode.INDEX)) {
            String token = normalize(word.word);
            if (token != null) {
                unique.add(token);
            }
        }
        return new ArrayList<>(unique);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        // tsquery/tsvector 的语法字符必须去掉，否则拼出来的表达式会解析失败。
        String token = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[&|!():'\\\\*\\s]", "");
        if (token.isEmpty() || STOP_WORDS.contains(token)) {
            return null;
        }
        return token.codePointCount(0, token.length()) == 1 && !isCjk(token.codePointAt(0)) ? null : token;
    }

    private static boolean isCjk(int codePoint) {
        return codePoint >= 0x4E00 && codePoint <= 0x9FFF;
    }

    /**
     * 词典指纹，进启动日志与配置指纹。换了词典这个值就会变，于是"召回率什么时候开始下降的"
     * 可以对着它查，而不是靠回忆。
     */
    public String dictionaryFingerprint() {
        // jieba 的默认词典随 jar 固定，用依赖坐标 + 一个固定探针串的切词结果做指纹：
        // 词典变了探针结果就会变，不需要去读 jar 里的词典文件。
        return "jieba-1.0.2:" + String.join(",", tokens("海外仓退货政策与退款到账时效"));
    }
}
