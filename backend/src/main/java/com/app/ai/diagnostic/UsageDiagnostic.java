package com.app.ai.diagnostic;

public record UsageDiagnostic(
        Integer inputTokenCount,
        Integer outputTokenCount,
        Integer embeddingInputCount,
        Integer embeddingBatchCount,
        Integer embeddingDimensions,
        Integer rerankDocumentCount,
        Integer rerankTopN,
        Integer streamEventCount) {
}
