(function () {
  var STORAGE_KEY = "xrag-prototype-v1-state";
  var FLASH_KEY = "xrag-prototype-v1-flash";

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function makeId() {
    return "doc-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 8);
  }

  function safeParse(raw) {
    try {
      return JSON.parse(raw);
    } catch (error) {
      return null;
    }
  }

  function previewFromContent(content) {
    var normalized = String(content || "").replace(/\s+/g, " ").trim();
    return normalized.slice(0, 120) || "暂无正文预览";
  }

  function normalizeTags(tags) {
    return Array.from(
      new Set(
        (tags || [])
          .map(function (tag) {
            return String(tag || "").trim();
          })
          .filter(Boolean)
      )
    );
  }

  function normalizeDocument(document) {
    var doc = clone(document);
    doc.tags = normalizeTags(doc.tags);
    doc.content_preview = doc.content_preview || previewFromContent(doc.content_clean || doc.content_raw || "");
    doc.parse_error_message = doc.parse_error_message || "";
    doc.is_deleted = Boolean(doc.is_deleted);
    return doc;
  }

  function initialState() {
    return {
      version: "v1",
      initialized_at: new Date().toISOString(),
      documents: (window.XRAG_SEED.documents || []).map(normalizeDocument)
    };
  }

  function loadState() {
    var existing = safeParse(window.localStorage.getItem(STORAGE_KEY));
    if (!existing || !Array.isArray(existing.documents)) {
      var fresh = initialState();
      persistState(fresh);
      return fresh;
    }
    existing.documents = existing.documents.map(normalizeDocument);
    return existing;
  }

  function persistState(state) {
    var payload = clone(state);
    payload.updated_at = new Date().toISOString();
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    return payload;
  }

  function applyProcessingResult(doc) {
    if (doc.pending_resolution === "failed") {
      doc.parse_status = "failed";
      doc.parse_error_message = doc.parse_error_message || "解析失败，请稍后重试。";
      doc.content_clean = doc.content_clean || "";
      doc.content_preview = doc.content_preview || "解析失败，暂无正文。";
      return;
    }

    doc.parse_status = "success";
    doc.parse_error_message = "";
    if (!doc.content_clean || doc.content_clean === "系统仍在处理该文档。") {
      var title = doc.title || doc.file_name || "新导入文档";
      doc.content_clean =
        "这是由原型自动生成的解析结果，用于模拟“处理中 -> 成功可搜索”的闭环。文档《" +
        title +
        "》已完成文本抽取，并被纳入统一检索入口。";
    }
    doc.content_preview = previewFromContent(doc.content_clean);
  }

  function tickProcessing() {
    var state = loadState();
    var now = Date.now();
    var changed = false;

    state.documents.forEach(function (doc) {
      if (doc.parse_status === "processing" && doc.processing_due_at) {
        if (Date.parse(doc.processing_due_at) <= now) {
          applyProcessingResult(doc);
          delete doc.processing_due_at;
          delete doc.pending_resolution;
          changed = true;
        }
      }
    });

    if (changed) {
      persistState(state);
    }

    return clone(state);
  }

  function getDocuments() {
    return tickProcessing().documents.filter(function (doc) {
      return !doc.is_deleted;
    });
  }

  function getDocument(id) {
    return getDocuments().find(function (doc) {
      return doc.id === id;
    }) || null;
  }

  function createManualDocument(payload) {
    var state = tickProcessing();
    var content = String(payload.content || "").trim();
    var doc = normalizeDocument({
      id: makeId(),
      title: String(payload.title || "").trim() || "未命名文本记录",
      content_raw: content,
      content_clean: content,
      content_preview: previewFromContent(content),
      source_type: "text",
      source_origin: "manual",
      source_url: "",
      file_name: "",
      mime_type: "text/plain",
      tags: normalizeTags(payload.tags),
      created_at: new Date().toISOString(),
      imported_at: new Date().toISOString(),
      parse_status: "success",
      parse_error_message: "",
      content_hash: makeId(),
      is_deleted: false
    });

    state.documents.unshift(doc);
    persistState(state);
    return doc;
  }

  function ingestFilePayload(payload) {
    var state = tickProcessing();
    var now = new Date();
    var extension = (payload.file_name.split(".").pop() || "").toLowerCase();
    var isSupported = ["txt", "md", "pdf"].indexOf(extension) >= 0;
    var isPdf = extension === "pdf";
    var isScanLike = /scan|ocr|扫描/i.test(payload.file_name);

    var doc = normalizeDocument({
      id: makeId(),
      title: payload.title || payload.file_name.replace(/\.[^/.]+$/, ""),
      content_raw: payload.content_raw || "",
      content_clean: payload.content_clean || payload.content_raw || "",
      content_preview: previewFromContent(payload.content_raw || payload.content_clean || ""),
      source_type: isPdf ? "pdf" : "file",
      source_origin: "upload",
      source_url: "",
      file_name: payload.file_name,
      mime_type: payload.mime_type || "application/octet-stream",
      tags: normalizeTags(payload.tags || []),
      created_at: now.toISOString(),
      imported_at: now.toISOString(),
      parse_status: "pending",
      parse_error_message: "",
      content_hash: makeId(),
      is_deleted: false
    });

    if (!isSupported) {
      doc.parse_status = "failed";
      doc.parse_error_message = "文件格式不支持，当前原型仅支持 TXT、MD 与文本型 PDF。";
      doc.content_preview = "格式不支持，无法建立索引。";
    } else if (isPdf) {
      doc.parse_status = "processing";
      doc.content_clean = "系统仍在处理该文档。";
      doc.content_preview = "PDF 已接收，正在解析文本...";
      doc.processing_due_at = new Date(now.getTime() + 2500).toISOString();
      doc.pending_resolution = isScanLike ? "failed" : "success";
      if (isScanLike) {
        doc.parse_error_message = "OCR 失败，当前原型未启用扫描版 PDF 识别。";
      }
    } else {
      doc.parse_status = "success";
      doc.content_preview = previewFromContent(doc.content_clean);
    }

    state.documents.unshift(doc);
    persistState(state);
    return doc;
  }

  function updateTags(id, tags) {
    var state = tickProcessing();
    var target = state.documents.find(function (doc) {
      return doc.id === id;
    });

    if (!target) {
      return null;
    }

    target.tags = normalizeTags(tags);
    persistState(state);
    return clone(target);
  }

  function retryDocument(id) {
    var state = tickProcessing();
    var target = state.documents.find(function (doc) {
      return doc.id === id;
    });

    if (!target) {
      return null;
    }

    target.parse_status = "processing";
    target.parse_error_message = "";
    target.processing_due_at = new Date(Date.now() + 2500).toISOString();
    target.pending_resolution = "success";
    target.content_preview = "重新加入解析队列，正在处理...";
    persistState(state);
    return clone(target);
  }

  function reset() {
    var fresh = initialState();
    persistState(fresh);
    window.localStorage.removeItem(FLASH_KEY);
    return clone(fresh);
  }

  function seedDemoBatch() {
    ingestFilePayload({
      file_name: "weekly-insights.md",
      mime_type: "text/markdown",
      content_raw: "本周重点：先提升关键词检索体验，再评估 OCR 与链接导入能力。",
      tags: ["周报", "产品策略"]
    });

    ingestFilePayload({
      file_name: "scan-receipt.pdf",
      mime_type: "application/pdf",
      content_raw: "",
      tags: ["财务", "异常"]
    });
  }

  function setFlashMessage(message) {
    window.sessionStorage.setItem(FLASH_KEY, JSON.stringify(message));
  }

  function consumeFlashMessage() {
    var message = safeParse(window.sessionStorage.getItem(FLASH_KEY));
    window.sessionStorage.removeItem(FLASH_KEY);
    return message;
  }

  function buildSnippet(doc, query) {
    var text = (doc.content_clean || doc.content_raw || "").replace(/\s+/g, " ").trim();
    if (!text) {
      return doc.content_preview || "暂无可预览内容";
    }

    if (!query) {
      return text.slice(0, 120);
    }

    var lower = text.toLowerCase();
    var index = lower.indexOf(query.toLowerCase());
    if (index < 0) {
      return text.slice(0, 120);
    }

    var start = Math.max(0, index - 24);
    var end = Math.min(text.length, index + query.length + 42);
    return (start > 0 ? "..." : "") + text.slice(start, end) + (end < text.length ? "..." : "");
  }

  function matchesTimeRange(doc, timeRange) {
    if (!timeRange || timeRange === "all") {
      return true;
    }

    var days = timeRange === "7d" ? 7 : timeRange === "30d" ? 30 : null;
    if (!days) {
      return true;
    }

    var importedAt = Date.parse(doc.imported_at);
    return importedAt >= Date.now() - days * 24 * 60 * 60 * 1000;
  }

  function searchDocuments(query, filters) {
    var currentQuery = String(query || "").trim();
    var normalizedQuery = currentQuery.toLowerCase();
    var selectedTag = filters.tag || "all";
    var selectedSource = filters.source || "all";
    var selectedStatus = filters.status || "all";
    var selectedRange = filters.timeRange || "all";

    var results = getDocuments()
      .filter(function (doc) {
        if (selectedSource !== "all" && doc.source_type !== selectedSource) {
          return false;
        }
        if (selectedStatus !== "all" && doc.parse_status !== selectedStatus) {
          return false;
        }
        if (selectedTag !== "all" && doc.tags.indexOf(selectedTag) < 0) {
          return false;
        }
        if (!matchesTimeRange(doc, selectedRange)) {
          return false;
        }
        if (!normalizedQuery) {
          return true;
        }

        var haystack = [doc.title, doc.content_clean, doc.content_raw, doc.tags.join(" ")].join(" ").toLowerCase();
        return haystack.indexOf(normalizedQuery) >= 0;
      })
      .map(function (doc) {
        var score = 0;
        if (normalizedQuery) {
          if (String(doc.title || "").toLowerCase().indexOf(normalizedQuery) >= 0) {
            score += 3;
          }
          if (String(doc.content_clean || "").toLowerCase().indexOf(normalizedQuery) >= 0) {
            score += 2;
          }
          if (doc.tags.join(" ").toLowerCase().indexOf(normalizedQuery) >= 0) {
            score += 1;
          }
        }

        return {
          document: doc,
          score: score,
          snippet: buildSnippet(doc, currentQuery)
        };
      })
      .sort(function (left, right) {
        return right.score - left.score || Date.parse(right.document.imported_at) - Date.parse(left.document.imported_at);
      });

    return results;
  }

  function getSummaries() {
    var documents = getDocuments();
    var summary = {
      total: documents.length,
      success: 0,
      processing: 0,
      failed: 0,
      pending: 0,
      bySource: {},
      tags: {}
    };

    documents.forEach(function (doc) {
      summary[doc.parse_status] += 1;
      summary.bySource[doc.source_type] = (summary.bySource[doc.source_type] || 0) + 1;
      doc.tags.forEach(function (tag) {
        summary.tags[tag] = (summary.tags[tag] || 0) + 1;
      });
    });

    return summary;
  }

  function getAvailableTags() {
    return Object.keys(getSummaries().tags).sort(function (a, b) {
      return a.localeCompare(b, "zh-CN");
    });
  }

  window.XRagStore = {
    buildSnippet: buildSnippet,
    consumeFlashMessage: consumeFlashMessage,
    createManualDocument: createManualDocument,
    getAvailableTags: getAvailableTags,
    getDocument: getDocument,
    getDocuments: getDocuments,
    getSummaries: getSummaries,
    ingestFilePayload: ingestFilePayload,
    reset: reset,
    retryDocument: retryDocument,
    searchDocuments: searchDocuments,
    seedDemoBatch: seedDemoBatch,
    setFlashMessage: setFlashMessage,
    tickProcessing: tickProcessing,
    updateTags: updateTags
  };
})();

