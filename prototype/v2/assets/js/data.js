(function () {
  var documents = [
    {
      id: "doc-pdf-success",
      title: "季度复盘 PDF 摘要",
      source_type: "pdf",
      file_name: "quarterly-review-q1.pdf",
      size_label: "18 MB",
      upload_mode: "multipart",
      upload_status: "uploaded",
      parse_status: "success",
      diagnosis_code: "",
      diagnosis_summary: "对象完成校验通过，正文提取成功，已进入可搜索状态。",
      content_preview: "提取结果包含目录、章节标题和正文摘要，可在搜索页按关键词直接命中。",
      content_clean: "第一部分说明产品目标，第二部分记录增长试验，第三部分总结失败原因与下阶段建议。",
      imported_at: "2026-04-06 10:20",
      incident_ref: "",
      timeline: [
        { step: "发起上传", status: "success", note: "生成 multipart upload session 与 24 小时有效签名。" },
        { step: "上传分片", status: "success", note: "42 个分片全部上传完成。" },
        { step: "完成上传", status: "success", note: "客户端提交 ETag 列表。" },
        { step: "校验对象", status: "success", note: "服务端确认对象大小、etag 与 bucket 中对象一致。" },
        { step: "加入解析队列", status: "success", note: "worker 收到 parse_document job。" },
        { step: "解析 PDF", status: "success", note: "文本型 PDF 提取成功。" }
      ],
      tags: ["pdf", "季度复盘", "成功"]
    },
    {
      id: "doc-uploading",
      title: "品牌研究长文档",
      source_type: "pdf",
      file_name: "brand-research-deck.pdf",
      size_label: "83 MB",
      upload_mode: "multipart",
      upload_status: "uploading",
      parse_status: "pending",
      diagnosis_code: "multipart_part_failed",
      diagnosis_summary: "第 17 个分片上传超时，客户端保留 uploadId，允许续传。",
      content_preview: "尚未进入解析。系统需要先完成剩余分片并通过对象完整性校验。",
      content_clean: "",
      imported_at: "2026-04-06 10:43",
      incident_ref: "",
      timeline: [
        { step: "发起上传", status: "success", note: "已申请 uploadId=mp-240406-17。" },
        { step: "上传分片", status: "processing", note: "第 17/42 个分片超时，建议重试该分片而不是重头上传。" },
        { step: "完成上传", status: "pending", note: "等待全部分片成功。" },
        { step: "校验对象", status: "pending", note: "对象尚未形成。" }
      ],
      tags: ["分片上传", "处理中", "pdf"]
    },
    {
      id: "doc-object-missing",
      title: "供应商扫描件样本",
      source_type: "file",
      file_name: "vendor-scan-april.pdf",
      size_label: "11 MB",
      upload_mode: "single",
      upload_status: "failed",
      parse_status: "failed",
      diagnosis_code: "object_missing_on_complete",
      diagnosis_summary: "客户端发起 complete 后，对象在 bucket 中不存在，疑似直传未完成或公网存储域名配置异常。",
      content_preview: "该文档还未进入可检索状态，用户需要先修复对象完整性问题。",
      content_clean: "",
      imported_at: "2026-04-06 09:58",
      incident_ref: "INC-240406-03",
      timeline: [
        { step: "发起上传", status: "success", note: "签名地址已发放。" },
        { step: "单对象上传", status: "unknown", note: "浏览器报告 200，但对象校验失败。" },
        { step: "完成上传", status: "failed", note: "HeadObject 返回 not found。" },
        { step: "创建事件", status: "failed", note: "需要检查存储公网域名与对象路径。" }
      ],
      tags: ["失败", "存储", "事件"]
    },
    {
      id: "doc-parse-failed",
      title: "扫描版合同样例",
      source_type: "pdf",
      file_name: "scan-contract-sample.pdf",
      size_label: "26 MB",
      upload_mode: "multipart",
      upload_status: "uploaded",
      parse_status: "failed",
      diagnosis_code: "pdf_parse_unsupported",
      diagnosis_summary: "文件已成功落盘，但当前阶段不做 OCR，worker 将其归类为不支持解析。",
      content_preview: "需要在后续版本补 OCR 或由用户换成文本型 PDF。",
      content_clean: "",
      imported_at: "2026-04-06 08:31",
      incident_ref: "INC-240406-04",
      timeline: [
        { step: "发起上传", status: "success", note: "multipart 会话创建成功。" },
        { step: "上传分片", status: "success", note: "9 个分片已全部上传。" },
        { step: "完成上传", status: "success", note: "对象校验通过。" },
        { step: "加入解析队列", status: "success", note: "worker 开始解析。" },
        { step: "解析 PDF", status: "failed", note: "检测到扫描版 PDF，当前未启用 OCR。" }
      ],
      tags: ["pdf", "失败", "OCR 后续支持"]
    }
  ];

  var incidents = [
    {
      id: "INC-240406-03",
      title: "上传完成失败：对象在存储中缺失",
      source: "upload",
      severity: "high",
      status: "open",
      timestamp: "2026-04-06 10:01",
      summary: "complete 接口返回对象缺失，影响用户文件导入闭环。",
      target_doc: "doc-object-missing"
    },
    {
      id: "INC-240406-04",
      title: "Worker 拒绝扫描版 PDF：当前未启用 OCR",
      source: "parse",
      severity: "medium",
      status: "tracked",
      timestamp: "2026-04-06 08:39",
      summary: "当前阶段明确不做 OCR，需要在 UI 中解释并提供替代路径。",
      target_doc: "doc-parse-failed"
    },
    {
      id: "CI-240404-01",
      title: "CI 生产环境 smoke 超时",
      source: "ci",
      severity: "medium",
      status: "resolved",
      timestamp: "2026-04-04 21:13",
      summary: "部署完成但 smoke 超时，后续通过 registry 重试与 timeout 调整修复。",
      target_doc: ""
    }
  ];

  var services = [
    { name: "API", status: "healthy", detail: "health / ready 均通过" },
    { name: "Worker", status: "warning", detail: "pdf unsupported 仍会产生失败任务" },
    { name: "PostgreSQL", status: "healthy", detail: "最近无连接饱和" },
    { name: "Redis / Queue", status: "healthy", detail: "backlog 3，处于可控范围" },
    { name: "对象存储", status: "warning", detail: "公网存储域名与直传配置需持续监控" }
  ];

  var rollback = {
    current_tag: "b997aea",
    previous_tag: "431e4cc",
    deploy_window: "2026-04-06 11:10 CST",
    steps: [
      "确认当前事故是否仅影响 web / upload 路径。",
      "把 XRAG_*_IMAGE 回切到上一稳定 tag。",
      "执行远端 docker compose rollout 并重跑 smoke。",
      "在事件中记录回滚原因和恢复时间。"
    ]
  };

  function metrics() {
    var successCount = documents.filter(function (doc) { return doc.parse_status === "success"; }).length;
    var failedCount = documents.filter(function (doc) { return doc.parse_status === "failed"; }).length;
    var processingCount = documents.filter(function (doc) { return doc.parse_status === "processing" || doc.upload_status === "uploading"; }).length;

    return [
      { label: "可搜索文档", value: String(successCount), hint: "已完成解析并可在搜索中命中" },
      { label: "处理中任务", value: String(processingCount), hint: "包含 multipart 上传中与 worker 解析中" },
      { label: "失败诊断项", value: String(failedCount), hint: "需要用户或开发者采取动作" },
      { label: "未关闭事件", value: String(incidents.filter(function (item) { return item.status !== "resolved"; }).length), hint: "导入链路或 CI 当前仍有未关闭事件" }
    ];
  }

  function queryDocs(filters) {
    var query = (filters.query || "").trim().toLowerCase();
    var status = filters.status || "all";
    var source = filters.source || "all";
    var diagnosis = filters.diagnosis || "all";

    return documents.filter(function (doc) {
      var haystack = [doc.title, doc.file_name, doc.diagnosis_summary, doc.content_preview, doc.tags.join(" ")].join(" ").toLowerCase();
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

  window.XRagPrototypeV2 = {
    documents: documents,
    incidents: incidents,
    metrics: metrics,
    queryDocs: queryDocs,
    getDocument: getDocument,
    services: services,
    rollback: rollback
  };
})();
