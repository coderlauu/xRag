package com.app.ai.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DiagnosticPrivacyTests {

    @Test
    void 诊断DTO和builder不存在正文原始错误或秘密承载字段() {
        List<Class<?>> types = List.of(
                CallDiagnosticBuilder.class,
                CompletedCallDiagnostic.class,
                RouteCandidateDiagnostic.class,
                RouteEventDiagnostic.class,
                AttemptDiagnostic.class,
                EmbeddingBatchExecutionDiagnostic.class);

        List<String> names = types.stream()
                .flatMap(type -> Stream.concat(
                        Stream.of(type.getDeclaredFields()).map(field -> field.getName()),
                        type.isRecord()
                                ? Stream.of(type.getRecordComponents()).map(RecordComponent::getName)
                                : Stream.empty()))
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(names).noneMatch(name -> name.matches(
                ".*(prompt|content|message|toolargument|raw|authorization|secret|apikey|credential).*"));
    }
}
