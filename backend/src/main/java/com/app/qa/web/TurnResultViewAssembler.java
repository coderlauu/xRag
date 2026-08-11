package com.app.qa.web;

import com.app.knowledge.search.ChunkDisplayLookup;
import com.app.qa.config.QaToolProperties;
import com.app.qa.retrieval.RetrievalResultRepository;
import com.app.qa.tool.ToolCallRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把 3B 的落库结果组装成 API 的 {@code retrieval} 与 {@code tools} 两个块（契约 §9）。
 *
 * <p>放在 {@code web} 包而不是各自的领域包：它的职责恰好是"决定什么能出去"，
 * 与三个不该出去的东西一一对应——分数（来源表本来就不存）、chunk 正文（另经知识库接口按需取）、
 * Schema 原文与异常 message（只取归一化码值与参数元信息）。一处组装意味着隐私扫描测试
 * 有唯一的靶子。
 */
@Component
public class TurnResultViewAssembler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TurnResultViewAssembler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RetrievalResultRepository retrievals;
    private final ToolCallRepository toolCalls;
    private final ChunkDisplayLookup displays;
    private final QaToolProperties toolProperties;

    public TurnResultViewAssembler(
            RetrievalResultRepository retrievals,
            ToolCallRepository toolCalls,
            ChunkDisplayLookup displays,
            QaToolProperties toolProperties) {
        this.retrievals = retrievals;
        this.toolCalls = toolCalls;
        this.displays = displays;
        this.toolProperties = toolProperties;
    }

    /** turn 未进过 3B 时返回 {@code null}——契约 §9 要求此时 {@code retrieval} 就是 null。 */
    public TurnResultView.Retrieval retrieval(UUID turnId) {
        var view = retrievals.findView(turnId).orElse(null);
        if (view == null) {
            return null;
        }
        var chunkIds = view.sources().stream().map(RetrievalResultRepository.SourceRow::chunkId).toList();
        Map<Long, ChunkDisplayLookup.ChunkDisplay> byChunk = displays.displaysOf(chunkIds);

        var sources = new ArrayList<TurnResultView.Source>(view.sources().size());
        for (var row : view.sources()) {
            var display = byChunk.get(row.chunkId());
            if (display == null) {
                // 分块被物理删除（向量与关键词索引是物理删除，分块本身逻辑删除，但迁移或运维
                // 清理仍可能让这一行找不到）。条目照样返回，界面按"文档已删除"渲染——
                // 静默丢掉它会让来源条数与 after_rerank 对不上，那种不一致更难查。
                LOGGER.warn("retrieval source chunk {} has no display row for turn {}", row.chunkId(), turnId);
                sources.add(new TurnResultView.Source(
                        row.ordinal(), row.chunkId(), null, null, null, null, null, true));
                continue;
            }
            sources.add(new TurnResultView.Source(
                    row.ordinal(),
                    display.chunkId(),
                    display.knowledgeBaseId(),
                    display.knowledgeBaseName(),
                    display.documentId(),
                    display.documentName(),
                    display.chunkIndex(),
                    display.documentDeleted()));
        }

        var kbIds = view.failedTargets().stream()
                .map(RetrievalResultRepository.FailedTargetRow::knowledgeBaseId)
                .filter(id -> id != null)
                .toList();
        Map<Long, String> kbNames = displays.knowledgeBaseNames(kbIds);
        var failedTargets = view.failedTargets().stream()
                .map(row -> new TurnResultView.FailedTarget(
                        row.knowledgeBaseId(),
                        row.knowledgeBaseId() == null ? null : kbNames.get(row.knowledgeBaseId()),
                        row.failureCode()))
                .toList();

        return new TurnResultView.Retrieval(
                view.status().name(),
                view.partial(),
                view.rerankApplied(),
                view.degradation(),
                sources,
                failedTargets);
    }

    /** 没有工具调用时返回空列表——契约 §9 要求 {@code tools} 是 {@code []} 而不是 null。 */
    public List<TurnResultView.Tool> tools(UUID turnId) {
        Map<String, QaToolProperties.Tool> whitelist = new LinkedHashMap<>();
        for (QaToolProperties.Tool tool : toolProperties.getWhitelist()) {
            whitelist.put(tool.getName(), tool);
        }
        var views = new ArrayList<TurnResultView.Tool>();
        for (ToolCallRepository.ToolCallRow row : toolCalls.findByTurn(turnId)) {
            QaToolProperties.Tool definition = whitelist.get(row.toolName());
            views.add(new TurnResultView.Tool(
                    row.id(),
                    row.ordinal(),
                    row.toolName(),
                    definition == null ? row.toolName() : definition.getDisplayName(),
                    definition == null ? null : definition.getDescription(),
                    row.sideEffect(),
                    row.status().name(),
                    parameters(definition, row.argumentsJson()),
                    row.resultText(),
                    row.failureCode() == null ? null : row.failureCode().name(),
                    row.latencyMs()));
        }
        return views;
    }

    /**
     * 把复核通过的参数 JSON 摊成参数表。
     *
     * <p>遍历的是<b>白名单声明的参数</b>而不是 JSON 里的键：这样即便存的 JSON 里多了什么，
     * 也出不到响应里——复核已经挡过一次，这是第二道闸。缺省的可选参数值为 {@code null}，
     * 条目仍出现，因为界面要显示"这个可选参数没填"。
     */
    private List<TurnResultView.Parameter> parameters(QaToolProperties.Tool definition, String argumentsJson) {
        if (definition == null || argumentsJson == null || argumentsJson.isBlank()) {
            return List.of();
        }
        JsonNode arguments;
        try {
            arguments = MAPPER.readTree(argumentsJson);
        } catch (Exception exception) {
            // 落库的参数 JSON 读不动是数据问题，不该把整个 turn 响应打挂：参数表给空，
            // 其余字段照常返回。异常本身不进响应（契约 §12）。
            LOGGER.warn("tool call arguments are not readable json for tool {}", definition.getName());
            return List.of();
        }
        var parameters = new ArrayList<TurnResultView.Parameter>();
        for (QaToolProperties.Tool.ParameterMeta meta : definition.getParameterMetas()) {
            JsonNode value = arguments.get(meta.key());
            parameters.add(new TurnResultView.Parameter(
                    meta.displayName(),
                    value == null || value.isNull() ? null : value.asText(),
                    meta.required()));
        }
        return List.copyOf(parameters);
    }
}
