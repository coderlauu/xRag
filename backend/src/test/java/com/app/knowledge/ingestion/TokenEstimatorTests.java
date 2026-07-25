package com.app.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEstimatorTests {

    @Test
    void 中文按一字一token() {
        assertThat(TokenEstimator.estimate("知识库分块")).isEqualTo(5);
    }

    @Test
    void ascii按四字符一token并向上取整() {
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("abcde")).isEqualTo(2);
    }

    @Test
    void 其他字符按两字符一token() {
        assertThat(TokenEstimator.estimate("Ωπ")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("Ωπλ")).isEqualTo(2);
    }

    @Test
    void 混排时各类分别计算后相加() {
        // 中文 2 + ascii 4 个("abcd")→1
        assertThat(TokenEstimator.estimate("分块abcd")).isEqualTo(3);
    }

    @Test
    void 空串为零() {
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    /** emoji 是补充平面字符（两个 char）——按码点算才是 1 个"其他字符"，不是 2 个。 */
    @Test
    void 补充平面字符按码点计数() {
        assertThat(TokenEstimator.estimate("🙂🙂")).isEqualTo(1);
    }
}
