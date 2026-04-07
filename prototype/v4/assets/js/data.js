(function () {
  var documents = [
    {
      id: "doc-roadmap-ready",
      title: "春季产品路线图公告",
      source_type: "link",
      file_name: "",
      size_label: "网页正文",
      upload_status: "uploaded",
      parse_status: "success",
      index_status: "ready",
      diagnosis_code: "",
      diagnosis_summary: "已完成 chunk、embedding 与 citation locator 生成，可直接进入 semantic retrieval。",
      freshness_summary: "索引于 2026-04-07 20:42 更新，当前为 ready。",
      content_preview: "公告明确提到五月前优先投入混合检索、引用链路与 freshness 监控。",
      content_clean: "五月前优先投入混合检索、引用链路与索引 freshness，确保 AI 问答可解释、可验证。",
      retrieval_hint: "标题命中“路线图”，正文语义接近“优先投入的能力”，官方站点带轻量权重。",
      imported_at: "2026-04-07 20:18",
      source_url: "https://news.example.com/roadmap-qa",
      tags: ["路线图", "混合检索", "引用"],
      chunks: [
        {
          label: "Roadmap / Retrieval",
          quote: "五月前优先投入混合检索、引用链路与索引 freshness。",
          locator: "anchor: roadmap-retrieval"
        },
        {
          label: "Roadmap / Trust",
          quote: "若证据不足，应优先拒答而不是输出模糊结论。",
          locator: "anchor: roadmap-trust"
        }
      ],
      timeline: [
        { step: "抓取正文", status: "success", note: "链接正文抽取成功。" },
        { step: "切 chunk", status: "success", note: "生成 9 个可引用片段。" },
        { step: "生成 embedding", status: "success", note: "向量写入 pgvector。" },
        { step: "验证引用定位", status: "success", note: "chunk locator 已可回跳。" }
      ],
      cited_by: ["sess-priority-answer", "sess-refusal-boundary"]
    },
    {
      id: "doc-retro-ready",
      title: "Phase 1C 复盘备忘",
      source_type: "text",
      file_name: "phase-1c-retro.md",
      size_label: "Markdown",
      upload_status: "uploaded",
      parse_status: "success",
      index_status: "ready",
      diagnosis_code: "",
      diagnosis_summary: "文本导入稳定，索引 ready，可直接进入 hybrid retrieval。",
      freshness_summary: "索引于 2026-04-07 20:35 更新，当前为 ready。",
      content_preview: "复盘强调如果没有 citation 与评估，AI 问答会成为不可信的表层增强。",
      content_clean: "复盘结论认为下一阶段应优先冻结 citation、scope 与 evaluation contract，再引入生成式答案。",
      retrieval_hint: "正文和标签都与“下一阶段优先级”强相关。",
      imported_at: "2026-04-07 19:52",
      source_url: "",
      tags: ["复盘", "评估", "scope"],
      chunks: [
        {
          label: "Retro / Trust",
          quote: "下一阶段应先冻结 citation、scope 与 evaluation contract，再引入生成式答案。",
          locator: "section: retro-trust"
        },
        {
          label: "Retro / Rollout",
          quote: "若没有固定评估集，团队无法判断答案变好还是只是变多。",
          locator: "section: retro-rollout"
        }
      ],
      timeline: [
        { step: "保存文本", status: "success", note: "导入成功并进入解析。" },
        { step: "切 chunk", status: "success", note: "生成 6 个文本 chunk。" },
        { step: "生成 embedding", status: "success", note: "语义索引写入完成。" },
        { step: "评估样本标注", status: "success", note: "被纳入 cross-doc 样例。" }
      ],
      cited_by: ["sess-priority-answer"]
    },
    {
      id: "doc-contract-ready",
      title: "供应商扫描合同（OCR 已进入问答索引）",
      source_type: "pdf",
      file_name: "vendor-contract-scan.pdf",
      size_label: "24 MB",
      upload_status: "uploaded",
      parse_status: "success",
      index_status: "ready",
      diagnosis_code: "",
      diagnosis_summary: "OCR 结果与页码定位已转换为 citation-ready chunks。",
      freshness_summary: "索引于 2026-04-07 20:10 更新，当前为 ready。",
      content_preview: "第三条约定违约金比例为合同金额的 8%，可用于单文档事实问答。",
      content_clean: "本合同适用于 2026 年二季度采购项目，第三条约定违约金比例为合同金额的 8%。",
      retrieval_hint: "关键词命中“违约金”，OCR 文本语义相似度高，页码定位完整。",
      imported_at: "2026-04-07 18:20",
      source_url: "",
      page_count: 12,
      tags: ["OCR", "合同", "单事实"],
      chunks: [
        {
          label: "Page 3 / Clause 3",
          quote: "第三条约定违约金比例为合同金额的 8%。",
          locator: "page: 3"
        },
        {
          label: "Page 1 / Title",
          quote: "本合同适用于 2026 年二季度采购项目。",
          locator: "page: 1"
        }
      ],
      timeline: [
        { step: "OCR 成功", status: "success", note: "12 页文本已抽取并保留页码。" },
        { step: "切 chunk", status: "success", note: "按页码与条款切成 14 个块。" },
        { step: "生成 embedding", status: "success", note: "合同类向量写入完成。" },
        { step: "验证 citation", status: "success", note: "引用可跳到 page 3 条款。" }
      ],
      cited_by: ["sess-contract-answer"]
    },
    {
      id: "doc-filter-stale",
      title: "搜索高级过滤器草案",
      source_type: "text",
      file_name: "search-filter-draft.md",
      size_label: "Markdown",
      upload_status: "uploaded",
      parse_status: "success",
      index_status: "stale",
      diagnosis_code: "index_embedding_failed",
      diagnosis_summary: "文档已更新，但最新段落尚未完成 embedding；当前只能走 keyword baseline。",
      freshness_summary: "最近内容变更于 2026-04-07 21:08，索引仍为 stale。",
      content_preview: "文档提到了高级过滤器，但当前语义索引仍停留在旧版本。",
      content_clean: "高级过滤器草案包含来源类型、时间范围和诊断码等组合筛选策略。",
      retrieval_hint: "关键词可命中“高级过滤器”，但 semantic hit 暂未更新。",
      imported_at: "2026-04-07 17:48",
      source_url: "",
      tags: ["过滤器", "stale", "索引"],
      chunks: [
        {
          label: "Draft / Filters",
          quote: "高级过滤器草案包含来源类型、时间范围和诊断码组合筛选。",
          locator: "section: draft-filters"
        }
      ],
      timeline: [
        { step: "文本更新", status: "success", note: "文档内容已变更。" },
        { step: "标记 stale", status: "warning", note: "当前 retrieval 仍使用旧 embedding。" },
        { step: "生成 embedding", status: "failed", note: "provider timeout，等待重建。" }
      ],
      cited_by: []
    }
  ];

  var sessions = [
    {
      id: "sess-priority-answer",
      question: "接下来两个月最值得投入的能力是什么？",
      scope_mode: "search_result",
      scope_summary: "搜索结果：路线图公告 + Phase 1C 复盘",
      retrieval_mode: "hybrid",
      answer_status: "answered",
      answer_summary: "基于当前知识库，五月前最值得投入的是混合检索、引用链路与索引 freshness。路线图给出了业务优先级，复盘进一步说明 citation、scope 与 evaluation contract 必须先冻结。",
      diagnosis_code: "",
      refusal_reason: "",
      latency_ms: 4210,
      token_cost: "$0.014",
      freshness_summary: "2 篇主证据均为 ready，无 stale chunk 被纳入答案。",
      steps: [
        { step: "编译 scope", status: "success", note: "限定到当前搜索结果中的 2 篇核心文档。" },
        { step: "执行 hybrid retrieval", status: "success", note: "keyword 命中路线图标题，semantic 命中复盘结论。" },
        { step: "生成答案", status: "success", note: "答案摘要绑定到 2 个 claim slot。" },
        { step: "校验 citation", status: "success", note: "每个核心 claim 都已绑定 chunk。" }
      ],
      citations: [
        {
          document_id: "doc-roadmap-ready",
          quote_text: "五月前优先投入混合检索、引用链路与索引 freshness。",
          locator: "路线图 / Retrieval"
        },
        {
          document_id: "doc-retro-ready",
          quote_text: "下一阶段应先冻结 citation、scope 与 evaluation contract，再引入生成式答案。",
          locator: "Retro / Trust"
        }
      ],
      retrieval_hits: [
        { document_id: "doc-roadmap-ready", lexical_score: 0.62, semantic_score: 0.88, rerank_score: 0.93, mode: "hybrid", used_in_answer: true, explanation: "标题命中 + 语义高相似 + freshness 高" },
        { document_id: "doc-retro-ready", lexical_score: 0.33, semantic_score: 0.84, rerank_score: 0.89, mode: "hybrid", used_in_answer: true, explanation: "问题语义与复盘结论高度相似" },
        { document_id: "doc-filter-stale", lexical_score: 0.21, semantic_score: 0, rerank_score: 0.18, mode: "keyword", used_in_answer: false, explanation: "仅关键词弱命中，且索引 stale" }
      ]
    },
    {
      id: "sess-contract-answer",
      question: "违约金比例是多少？",
      scope_mode: "document",
      scope_summary: "单篇文档：供应商扫描合同",
      retrieval_mode: "hybrid",
      answer_status: "answered",
      answer_summary: "合同第三条约定违约金比例为合同金额的 8%。该结论来自 OCR 文本第 3 页条款。",
      diagnosis_code: "",
      refusal_reason: "",
      latency_ms: 2870,
      token_cost: "$0.009",
      freshness_summary: "文档 ready，OCR 页码定位完整。",
      steps: [
        { step: "固定 scope", status: "success", note: "问题限定在单篇合同文档。" },
        { step: "召回 chunk", status: "success", note: "直接命中第 3 页条款。" },
        { step: "生成答案", status: "success", note: "答案为单事实摘录。" },
        { step: "校验 citation", status: "success", note: "引用回跳到 page 3。" }
      ],
      citations: [
        {
          document_id: "doc-contract-ready",
          quote_text: "第三条约定违约金比例为合同金额的 8%。",
          locator: "Page 3 / Clause 3"
        }
      ],
      retrieval_hits: [
        { document_id: "doc-contract-ready", lexical_score: 0.91, semantic_score: 0.8, rerank_score: 0.97, mode: "hybrid", used_in_answer: true, explanation: "关键词强命中 + OCR 语义高相似 + scope 固定" }
      ]
    },
    {
      id: "sess-filter-needs-scope",
      question: "哪些资料提到了高级过滤器？",
      scope_mode: "global",
      scope_summary: "全库",
      retrieval_mode: "semantic",
      answer_status: "needs_scope",
      answer_summary: "当前知识库存在与“高级过滤器”相关的草案，但其中一篇文档索引 stale，建议先限定到文本来源或先重建索引后再回答。",
      diagnosis_code: "retrieval_scope_empty",
      refusal_reason: "",
      latency_ms: 1960,
      token_cost: "$0.004",
      freshness_summary: "命中集合中存在 stale 文档，答案未直接生成。",
      steps: [
        { step: "执行 semantic retrieval", status: "success", note: "召回 2 篇相关文档。" },
        { step: "检查 freshness", status: "warning", note: "1 篇文档仍为 stale。" },
        { step: "生成答案", status: "needs_scope", note: "建议先缩小范围或重建索引。" }
      ],
      citations: [],
      retrieval_hits: [
        { document_id: "doc-filter-stale", lexical_score: 0.57, semantic_score: 0.79, rerank_score: 0.73, mode: "semantic", used_in_answer: false, explanation: "主题相关但索引 stale" },
        { document_id: "doc-roadmap-ready", lexical_score: 0.18, semantic_score: 0.46, rerank_score: 0.31, mode: "semantic", used_in_answer: false, explanation: "仅弱语义相近，不足以直接回答" }
      ]
    },
    {
      id: "sess-refusal-boundary",
      question: "团队版会在几月上线？",
      scope_mode: "global",
      scope_summary: "全库",
      retrieval_mode: "hybrid",
      answer_status: "refused",
      answer_summary: "",
      diagnosis_code: "answer_insufficient_evidence",
      refusal_reason: "当前知识库没有足够证据支持“团队版上线时间”的结论。现有文档只讨论检索、引用与评估，不包含发布时间承诺。",
      latency_ms: 1540,
      token_cost: "$0.002",
      freshness_summary: "retrieval 命中较弱，且没有文档直接讨论团队版时间表。",
      steps: [
        { step: "执行 hybrid retrieval", status: "success", note: "仅召回路线图与复盘中的弱相关段落。" },
        { step: "评估证据", status: "refused", note: "没有足够 claim-supporting citation。" }
      ],
      citations: [],
      retrieval_hits: [
        { document_id: "doc-roadmap-ready", lexical_score: 0.11, semantic_score: 0.22, rerank_score: 0.17, mode: "hybrid", used_in_answer: false, explanation: "弱相关，仅讨论优先级" },
        { document_id: "doc-retro-ready", lexical_score: 0.05, semantic_score: 0.19, rerank_score: 0.13, mode: "hybrid", used_in_answer: false, explanation: "弱相关，仅讨论 trust contract" }
      ]
    }
  ];

  var incidents = [
    {
      id: "ANS-240407-14",
      title: "高级过滤器草案索引 stale，导致 semantic retrieval 结果不稳定",
      source: "index",
      severity: "medium",
      status: "tracked",
      timestamp: "2026-04-07 21:10",
      summary: "文档已更新但 embedding 失败，当前建议先重建索引再做范围型问答。",
      target_doc: "doc-filter-stale"
    },
    {
      id: "ANS-240407-16",
      title: "团队版时间提问被正确拒答",
      source: "answer",
      severity: "low",
      status: "resolved",
      timestamp: "2026-04-07 21:18",
      summary: "拒答路径已命中 `answer_insufficient_evidence`，说明 trust boundary 生效。",
      target_doc: ""
    },
    {
      id: "ANS-240407-18",
      title: "Embedding provider p95 latency 上升",
      source: "provider",
      severity: "high",
      status: "open",
      timestamp: "2026-04-07 21:22",
      summary: "当前 embedding p95 达到 3.6s，可能影响 freshness 窗口。",
      target_doc: ""
    }
  ];

  var services = [
    { name: "API", status: "healthy", detail: "问答会话、retrieval trace 与 evidence 读取正常。" },
    { name: "Retrieval Orchestrator", status: "healthy", detail: "keyword / semantic / hybrid 三条路径都可用。" },
    { name: "Embedding Provider", status: "warning", detail: "p95 latency 上升，freshness 受影响。" },
    { name: "Answer Provider", status: "healthy", detail: "citation 校验通过率稳定。" },
    { name: "pgvector Index", status: "warning", detail: "存在 1 篇 stale 文档待回补。" }
  ];

  var rollback = {
    current_tag: "phase-2a-answer-canary",
    previous_tag: "phase-1c-search-stable",
    deploy_window: "2026-04-07 21:30 CST",
    steps: [
      "当 groundedness 或 citation coverage 下滑时，先关闭 answer generation，只保留 retrieval trace。",
      "当 semantic retrieval 不稳定时，降级为 keyword baseline，而不是一起回滚整个检索页。",
      "当 freshness lag 持续过高时，优先暂停新索引写入并清理 embedding backlog。",
      "只有 API、retrieval 与 provider 整体失稳时，才整体回切到 Phase 1C 搜索基线。"
    ]
  };

  var evaluation = {
    recall_at_10: "0.84",
    groundedness: "0.91",
    citation_coverage: "0.97",
    refusal_precision: "0.94",
    latency_p95: "7.4s",
    avg_token_cost: "$0.012",
    active_cases: 37
  };

  function metrics() {
    var answered = sessions.filter(function (item) { return item.answer_status === "answered"; }).length;
    var refused = sessions.filter(function (item) { return item.answer_status === "refused"; }).length;
    var staleDocs = documents.filter(function (doc) { return doc.index_status === "stale"; }).length;
    var readyDocs = documents.filter(function (doc) { return doc.index_status === "ready"; }).length;

    return [
      { label: "Ready 文档", value: String(readyDocs), hint: "已具备 citation-ready semantic index 的文档" },
      { label: "已回答问题", value: String(answered), hint: "当前样例中成功输出带引用答案的会话数" },
      { label: "正确拒答", value: String(refused), hint: "当前样例中被明确拒答的问题数" },
      { label: "Stale 文档", value: String(staleDocs), hint: "已导入但需要重建 embedding 的文档" }
    ];
  }

  function queryHits(sessionId, filters) {
    var session = getSession(sessionId);
    var query = (filters.query || "").trim().toLowerCase();
    var mode = filters.mode || "all";
    var source = filters.source || "all";
    var indexStatus = filters.index_status || "all";

    return session.retrieval_hits.filter(function (hit) {
      var doc = getDocument(hit.document_id);
      var haystack = [session.question, doc.title, doc.content_preview, hit.explanation, doc.tags.join(" ")].join(" ").toLowerCase();
      if (query && haystack.indexOf(query) === -1) {
        return false;
      }
      if (mode !== "all" && hit.mode !== mode) {
        return false;
      }
      if (source !== "all" && doc.source_type !== source) {
        return false;
      }
      if (indexStatus !== "all" && doc.index_status !== indexStatus) {
        return false;
      }
      return true;
    }).map(function (hit) {
      return {
        hit: hit,
        document: getDocument(hit.document_id)
      };
    });
  }

  function getSession(id) {
    return sessions.find(function (item) { return item.id === id; }) || sessions[0];
  }

  function getDocument(id) {
    return documents.find(function (doc) { return doc.id === id; }) || documents[0];
  }

  window.XRagPrototypeV4 = {
    documents: documents,
    evaluation: evaluation,
    getDocument: getDocument,
    getSession: getSession,
    incidents: incidents,
    metrics: metrics,
    queryHits: queryHits,
    rollback: rollback,
    services: services,
    sessions: sessions
  };
})();
