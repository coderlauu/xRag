package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.knowledge.keyword.ChineseTokenizer;
import org.junit.jupiter.api.Test;

/** 3B-KW-05、3B-KW-06、3B-KW-09。 */
class ChineseTokenizerTests {

    private final ChineseTokenizer tokenizer = new ChineseTokenizer();

    @Test
    void kw05_同一文本两次切词逐token相等() {
        // 索引期与查询期必须切出一致的 token，否则关键词通道安静地什么都召回不到。
        String text = "海外仓退货政策与退款到账时效";
        assertThat(tokenizer.tokens(text)).isEqualTo(tokenizer.tokens(text));
        assertThat(tokenizer.toTsvectorInput(text)).isEqualTo(tokenizer.toTsvectorInput(text));
    }

    @Test
    void kw06_中文长句切出可检索token() {
        var tokens = tokenizer.tokens("签收后 7 日内可发起退货，生鲜与定制类商品除外");
        assertThat(tokens).contains("退货").doesNotContain("");
    }

    @Test
    void kw06_中英混排与型号串保留() {
        var tokens = tokenizer.tokens("订单 ORD-20260806-1183 的 3C 数码退货");
        assertThat(String.join(" ", tokens)).contains("ord").contains("退货");
    }

    @Test
    void kw06_全角标点与单字符噪声被剔除() {
        assertThat(tokenizer.tokens("，。！？")).isEmpty();
        assertThat(tokenizer.tokens("a")).isEmpty();
        // 单个汉字保留——中文里单字是有意义的检索单元。
        assertThat(tokenizer.tokens("仓")).containsExactly("仓");
    }

    @Test
    void 切不出token时tsquery返回null而不是空表达式() {
        // 拼一个空 tsquery 交给 Postgres 会直接抛错，所以这里必须能被调用方识别。
        assertThat(tokenizer.toTsqueryInput("，。")).isNull();
        assertThat(tokenizer.toTsqueryInput("")).isNull();
        assertThat(tokenizer.toTsqueryInput(null)).isNull();
    }

    @Test
    void tsquery语法字符不会漏进表达式() {
        String tsquery = tokenizer.toTsqueryInput("退货 & 退款 | (发票)");
        assertThat(tsquery).isNotNull();
        assertThat(tsquery).doesNotContain("|").doesNotContain("(").doesNotContain(")");
    }

    @Test
    void kw09_词典指纹稳定且可辨识() {
        assertThat(tokenizer.dictionaryFingerprint())
                .isEqualTo(tokenizer.dictionaryFingerprint())
                .startsWith("jieba-1.0.2:");
    }
}
