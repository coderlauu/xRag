package com.app.knowledge.keyword;

import com.huaban.analysis.jieba.JiebaSegmenter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
     * 切词并拼成 {@code to_tsquery('simple', ...)} 的输入，词之间取 AND。
     *
     * <p>返回 {@code null} 表示这个查询切不出任何可检索的 token（例如纯标点）。调用方必须把它
     * 当成"关键词通道无结果"，而不是拼一个空 {@code tsquery} 给数据库——后者会直接抛错。
     */
    public String toTsqueryInput(String query) {
        List<String> tokens = tokens(query);
        return tokens.isEmpty() ? null : String.join(" & ", tokens);
    }

    /**
     * 保序去重的 token 列表。
     *
     * <p>去掉的三类：空白、单个非中日韩字符（"的""a""1" 这类单字符噪声在 AND 查询里几乎
     * 只会误伤召回）、以及 tsquery 语法字符。**不做停用词表**——第一版没有证据支撑一份中文
     * 停用词表的取舍，加了反而多一个会漂移的配置。
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
        if (token.isEmpty()) {
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
