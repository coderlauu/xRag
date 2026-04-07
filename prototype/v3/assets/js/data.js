(function () {
  var documents = [
    {
      id: "doc-ocr-success",
      title: "供应商扫描合同（OCR 成功）",
      source_type: "pdf",
      file_name: "vendor-contract-scan.pdf",
      size_label: "24 MB",
      upload_mode: "multipart",
      upload_status: "uploaded",
      parse_status: "success",
      diagnosis_code: "",
      diagnosis_summary: "扫描版 PDF 已完成 OCR、正文清洗和检索投影，可直接进入搜索。",
      content_preview: "命中解释显示“违约金”来自 OCR 正文第 3 页，标题命中“供应商合同”。",
      content_clean: "本合同适用于 2026 年二季度采购项目，第三条约定违约金比例为合同金额的 8%。",
      match_explanation: "标题命中“合同”，正文第 3 页 OCR 文本命中“违约金”，排序因标题命中与近期导入加权上浮。",
      imported_at: "2026-04-07 15:20",
      page_count: 12,
      source_url: "",
      incident_ref: "",
      timeline: [
        { step: "发起上传", status: "success", note: "创建 multipart 上传会话，分片阈值 5 MB。" },
        { step: "完成上传", status: "success", note: "对象校验通过，文件进入解析队列。" },
        { step: "提取 PDF 信息", status: "success", note: "检测到无文本层，进入 OCR 分支。" },
        { step: "执行 OCR", status: "success", note: "OCR 成功提取 12 页文本与页码映射。" },
        { step: "生成搜索投影", status: "success", note: "写入 search_text 与匹配摘要。" }
      ],
      tags: ["OCR", "合同", "成功"]
    },
    {
      id: "doc-ocr-processing",
      title: "发票影印件（OCR 处理中）",
      source_type: "pdf",
      file_name: "invoice-batch-scan.pdf",
      size_label: "31 MB",
      upload_mode: "multipart",
      upload_status: "uploaded",
      parse_status: "processing",
      diagnosis_code: "ocr_running",
      diagnosis_summary: "对象已完成校验，OCR worker 正在处理第 8 / 20 页。",
      content_preview: "当前尚未进入搜索结果，详情页需要明确显示 OCR 进度与预计完成时间。",
      content_clean: "",
      match_explanation: "",
      imported_at: "2026-04-07 15:43",
      page_count: 20,
      source_url: "",
      incident_ref: "",
      timeline: [
        { step: "发起上传", status: "success", note: "上传会话创建成功。" },
        { step: "完成上传", status: "success", note: "对象校验通过。" },
        { step: "进入 OCR 队列", status: "success", note: "已被 OCR worker 拉取。" },
        { step: "执行 OCR", status: "processing", note: "正在处理第 8 / 20 页。" },
        { step: "生成搜索投影", status: "pending", note: "等待 OCR 文本合并完成。" }
      ],
      tags: ["OCR", "处理中", "队列"]
    },
    {
      id: "doc-link-success",
      title: "站点公告：春季产品路线图",
      source_type: "link",
      file_name: "",
      size_label: "网页正文",
      upload_mode: "single",
      upload_status: "uploaded",
      parse_status: "success",
      diagnosis_code: "",
      diagnosis_summary: "链接抓取成功，已提取标题、正文、来源站点与发布时间。",
      content_preview: "搜索结果会展示“标题命中 / 正文命中 / 来源站点”三段解释。",
      content_clean: "春季产品路线图聚焦知识导入、智能检索与运维能力，预计在 5 月上线链接抓取增强能力。",
      match_explanation: "标题命中“路线图”，正文命中“链接抓取增强”，来源域名权重来自官方站点白名单。",
      imported_at: "2026-04-07 14:18",
      page_count: null,
      source_url: "https://news.example.com/roadmap",
      incident_ref: "",
      timeline: [
        { step: "提交链接", status: "success", note: "前端校验 URL 合法并创建抓取任务。" },
        { step: "抓取 HTML", status: "success", note: "下载正文页面成功，状态码 200。" },
        { step: "提取正文", status: "success", note: "正文抽取器成功移除导航与广告区域。" },
        { step: "写入搜索投影", status: "success", note: "标题、正文摘要和来源域写入完成。" }
      ],
      tags: ["链接", "正文抓取", "官方站点"]
    },
    {
      id: "doc-link-failed",
      title: "外部分析文章（抓取失败）",
      source_type: "link",
      file_name: "",
      size_label: "网页正文",
      upload_mode: "single",
      upload_status: "uploaded",
      parse_status: "failed",
      diagnosis_code: "link_fetch_blocked",
      diagnosis_summary: "目标站点返回反爬校验页，正文抽取失败，需提示用户改用复制正文或稍后重试。",
      content_preview: "失败文档仍应出现在搜索和详情里，便于排查为什么没进入可搜索状态。",
      content_clean: "",
      match_explanation: "",
      imported_at: "2026-04-07 13:56",
      page_count: null,
      source_url: "https://insight.example.net/report",
      incident_ref: "INC-240407-07",
      timeline: [
        { step: "提交链接", status: "success", note: "任务创建成功。" },
        { step: "抓取 HTML", status: "failed", note: "命中反爬页面，正文不可读。" },
        { step: "创建事件", status: "failed", note: "生成 incident 并建议用户切换为手工文本导入。" }
      ],
      tags: ["链接", "失败", "反爬"]
    }
  ];

  var incidents = [
    {
      id: "INC-240407-07",
      title: "链接抓取失败：目标站点返回反爬页面",
      source: "fetch",
      severity: "high",
      status: "open",
      timestamp: "2026-04-07 13:57",
      summary: "链接正文提取失败，当前建议改为手工文本导入或等待抓取策略升级。",
      target_doc: "doc-link-failed"
    },
    {
      id: "INC-240407-05",
      title: "OCR 队列积压告警",
      source: "ocr",
      severity: "medium",
      status: "tracked",
      timestamp: "2026-04-07 15:46",
      summary: "扫描件队列平均等待时间超过 4 分钟，需要扩 worker 并校验 OCR runtime。",
      target_doc: "doc-ocr-processing"
    },
    {
      id: "CI-240406-12",
      title: "CI 自动修复已成功修正文案漂移的 E2E 选择器",
      source: "ci",
      severity: "low",
      status: "resolved",
      timestamp: "2026-04-06 23:28",
      summary: "低风险自动修复链已验证可用，后续仅扩到规则型错误。",
      target_doc: ""
    }
  ];

  var services = [
    { name: "API", status: "healthy", detail: "链接提交、上传会话与详情读取正常。" },
    { name: "Worker", status: "healthy", detail: "文本解析与 OCR 调度都可用。" },
    { name: "OCR Runtime", status: "warning", detail: "平均处理时长升高，需看 backlog 与页数分布。" },
    { name: "抓取器", status: "warning", detail: "部分站点存在反爬拦截，需要分类与降级路径。" },
    { name: "搜索投影", status: "healthy", detail: "匹配解释与排序字段已正常输出。" }
  ];

  var rollback = {
    current_tag: "phase-1c-canary",
    previous_tag: "phase-1b-stable",
    deploy_window: "2026-04-07 16:10 CST",
    steps: [
      "先判断问题发生在 OCR、抓取器还是搜索投影，而不是直接回滚整套链路。",
      "若 OCR runtime 异常，可先关闭扫描件 OCR feature flag，保留文本型 PDF 主链路。",
      "若链接抓取异常，可先降级为只创建失败诊断，不阻塞既有上传链路。",
      "只有当 API / Worker / 搜索投影整体失稳时，才回切到上一稳定 tag。"
    ]
  };

  function metrics() {
    var successCount = documents.filter(function (doc) { return doc.parse_status === "success"; }).length;
    var failedCount = documents.filter(function (doc) { return doc.parse_status === "failed"; }).length;
    var processingCount = documents.filter(function (doc) { return doc.parse_status === "processing"; }).length;

    return [
      { label: "已可搜索", value: String(successCount), hint: "OCR 成功与链接抓取成功的文档总数" },
      { label: "处理中任务", value: String(processingCount), hint: "包含 OCR 和链接抓取中的文档" },
      { label: "失败待处理", value: String(failedCount), hint: "需要用户或运维执行补救动作" },
      { label: "未关闭事件", value: String(incidents.filter(function (item) { return item.status !== "resolved"; }).length), hint: "导入链路、OCR 与抓取相关事件" }
    ];
  }

  function queryDocs(filters) {
    var query = (filters.query || "").trim().toLowerCase();
    var status = filters.status || "all";
    var source = filters.source || "all";
    var diagnosis = filters.diagnosis || "all";

    return documents.filter(function (doc) {
      var haystack = [
        doc.title,
        doc.file_name,
        doc.source_url,
        doc.diagnosis_summary,
        doc.content_preview,
        doc.match_explanation,
        doc.tags.join(" ")
      ].join(" ").toLowerCase();
      if (query && haystack.indexOf(query) === -1) {
        return false;
      }
      if (status !== "all" && doc.parse_status !== status) {
        return false;
      }
      if (source !== "all" && doc.source_type !== source) {
        return false;
      }
      if (diagnosis !== "all" && doc.diagnosis_code !== diagnosis) {
        return false;
      }
      return true;
    });
  }

  function getDocument(id) {
    return documents.find(function (doc) { return doc.id === id; }) || documents[0];
  }

  window.XRagPrototypeV3 = {
    documents: documents,
    incidents: incidents,
    metrics: metrics,
    queryDocs: queryDocs,
    getDocument: getDocument,
    services: services,
    rollback: rollback
  };
})();
