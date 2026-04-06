(function () {
  var STATUS_COPY = {
    draft: "草稿",
    initiated: "已发起",
    uploading: "上传中",
    verifying: "校验中",
    uploaded: "已上传",
    pending: "待处理",
    processing: "处理中",
    success: "成功",
    failed: "失败",
    open: "待处理",
    tracked: "跟踪中",
    resolved: "已解决",
    healthy: "正常",
    warning: "告警",
    high: "高",
    medium: "中"
  };

  function escapeHtml(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function statusPill(status) {
    return '<span class="status-pill status-' + escapeHtml(status) + '">' + escapeHtml(STATUS_COPY[status] || status) + "</span>";
  }

  function tagsMarkup(tags) {
    return (tags || []).map(function (tag) {
      return '<span class="tag-chip">' + escapeHtml(tag) + "</span>";
    }).join("");
  }

  function navMarkup(active) {
    var items = [
      { key: "inbox", href: "index.html", label: "导入工作台" },
      { key: "search", href: "search.html", label: "搜索诊断" },
      { key: "detail", href: "detail.html", label: "文档控制台" },
      { key: "ops", href: "ops.html", label: "运维看板" }
    ];

    return items.map(function (item) {
      var cls = item.key === active ? "nav-link is-active" : "nav-link";
      return '<a class="' + cls + '" href="' + item.href + '">' + escapeHtml(item.label) + "</a>";
    }).join("");
  }

  function mountHeader(pageKey) {
    var host = document.querySelector("[data-global-header]");
    if (!host) {
      return;
    }

    host.innerHTML =
      '<div class="brand-block">' +
      '<a class="brand-mark" href="index.html">xr</a>' +
      '<div>' +
      '<p class="eyebrow">产品原型</p>' +
      '<h1>xRag v2 / Phase 1B</h1>' +
      "</div>" +
      "</div>" +
      '<nav class="main-nav">' + navMarkup(pageKey) + "</nav>" +
      '<div class="header-actions">' +
      '<a class="ghost-button" href="../README.md">原型说明</a>' +
      "</div>";
  }

  function metricCards(items) {
    return items.map(function (item) {
      return (
        '<article class="metric-card">' +
        "<span>" + escapeHtml(item.label) + "</span>" +
        "<strong>" + escapeHtml(item.value) + "</strong>" +
        '<p class="muted-copy">' + escapeHtml(item.hint) + "</p>" +
        "</article>"
      );
    }).join("");
  }

  function timelineMarkup(items) {
    return (items || []).map(function (item) {
      return (
        '<li class="timeline-item">' +
        '<div class="timeline-item__meta">' +
        statusPill(item.status) +
        '<strong>' + escapeHtml(item.step) + "</strong>" +
        "</div>" +
        '<p class="muted-copy">' + escapeHtml(item.note) + "</p>" +
        "</li>"
      );
    }).join("");
  }

  function docCard(doc, extra) {
    return (
      '<article class="list-card">' +
      '<div class="list-card__head">' +
      '<div>' +
      '<h4 class="list-title"><a href="detail.html?doc=' + encodeURIComponent(doc.id) + '">' + escapeHtml(doc.title) + "</a></h4>" +
      '<p class="muted-copy">' + escapeHtml(doc.file_name || "") + " · " + escapeHtml(doc.size_label || "") + "</p>" +
      "</div>" +
      '<div class="status-row">' +
      statusPill(doc.parse_status) +
      statusPill(doc.upload_status) +
      "</div>" +
      "</div>" +
      '<p class="list-copy">' + escapeHtml(extra || doc.content_preview || doc.diagnosis_summary) + "</p>" +
      '<div class="tag-row">' + tagsMarkup(doc.tags) + "</div>" +
      "</article>"
    );
  }

  function incidentCard(item) {
    return (
      '<article class="list-card">' +
      '<div class="list-card__head">' +
      '<div>' +
      '<h4 class="list-title">' + escapeHtml(item.title) + "</h4>" +
      '<p class="muted-copy">' + escapeHtml(item.timestamp) + " · " + escapeHtml(item.source) + "</p>" +
      "</div>" +
      '<div class="status-row">' + statusPill(item.status) + statusPill(item.severity) + "</div>" +
      "</div>" +
      '<p class="list-copy">' + escapeHtml(item.summary) + "</p>" +
      "</article>"
    );
  }

  function params() {
    return new URLSearchParams(window.location.search);
  }

  window.XRagPrototypeUI = {
    docCard: docCard,
    escapeHtml: escapeHtml,
    incidentCard: incidentCard,
    metricCards: metricCards,
    mountHeader: mountHeader,
    params: params,
    statusPill: statusPill,
    tagsMarkup: tagsMarkup,
    timelineMarkup: timelineMarkup
  };
})();
