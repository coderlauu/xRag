package com.app.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnconfiguredEmbeddingClientTests {

    @Test
    void reportsWhichEnvironmentVariableIsMissing() {
        assertThatThrownBy(() -> new UnconfiguredEmbeddingClient(1024).embed(List.of("x")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("EMBEDDING_API_KEY");
    }

    /** 维度不依赖 Key：启动时的维度校验在未配置 Key 的环境里也要能跑。 */
    @Test
    void stillReportsDimensions() {
        assertThat(new UnconfiguredEmbeddingClient(1024).dimensions()).isEqualTo(1024);
    }
}
