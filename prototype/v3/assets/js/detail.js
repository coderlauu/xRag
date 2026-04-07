(function () {
  var data = window.XRagPrototypeV3;
  var ui = window.XRagPrototypeUI;

  function render(doc) {
    var meta = doc.source_type === "link"
      ? (doc.source_url + " · " + doc.imported_at)
      : (doc.file_name + " · " + doc.size_label + " · " + doc.imported_at);

    document.querySelector("[data-detail-header]").innerHTML =
      '<div class="detail-header">' +
      '<div class="status-row">' + ui.statusPill(doc.upload_status) + ui.statusPill(doc.parse_status) + "</div>" +
      '<h2 class="detail-title">' + ui.escapeHtml(doc.title) + "</h2>" +
      '<p class="muted-copy">' + ui.escapeHtml(meta) + "</p>" +
      '<div class="tag-row">' + ui.tagsMarkup(doc.tags) + "</div>" +
      "</div>";

    document.querySelector("[data-detail-content]").innerHTML =
      '<div class="stack-list">' +
      '<div class="stack-list__item"><span>提取正文</span><strong>' + ui.escapeHtml(doc.content_clean || "尚未生成正文") + "</strong></div>" +
      '<div class="stack-list__item"><span>命中解释</span><strong>' + ui.escapeHtml(doc.match_explanation || "当前无匹配解释") + "</strong></div>" +
      (doc.page_count ? '<div class="stack-list__item"><span>页数</span><strong>' + ui.escapeHtml(doc.page_count) + "</strong></div>" : "") +
      (doc.source_url ? '<div class="stack-list__item"><span>来源链接</span><strong>' + ui.escapeHtml(doc.source_url) + "</strong></div>" : "") +
      "</div>";

    document.querySelector("[data-detail-timeline]").innerHTML = ui.timelineMarkup(doc.timeline);

    document.querySelector("[data-detail-upload]").innerHTML =
      '<div class="stack-list">' +
      '<div class="stack-list__item"><span>接入模式</span><strong>' + ui.escapeHtml(doc.source_type === "link" ? "链接导入" : "文件上传") + "</strong></div>" +
      '<div class="stack-list__item"><span>上传状态</span><strong>' + ui.escapeHtml(doc.upload_status) + "</strong></div>" +
      '<div class="stack-list__item"><span>解析状态</span><strong>' + ui.escapeHtml(doc.parse_status) + "</strong></div>" +
      "</div>";

    document.querySelector("[data-detail-diagnosis]").innerHTML =
      '<div class="callout">' +
      '<h4>' + ui.escapeHtml(doc.diagnosis_code || "当前无失败诊断") + "</h4>" +
      '<p class="list-copy">' + ui.escapeHtml(doc.diagnosis_summary) + "</p>" +
      "</div>";

    document.querySelector("[data-detail-incident]").innerHTML =
      doc.incident_ref
        ? '<div class="stack-list"><div class="stack-list__item"><span>关联事件</span><strong>' + ui.escapeHtml(doc.incident_ref) + '</strong></div><div class="stack-list__item"><span>推荐动作</span><strong>查看运维页、重试抓取或改为手工导入</strong></div></div>'
        : '<p class="muted-copy">当前没有关联事件，可继续到搜索页验证匹配解释和排序。</p>';

    document.querySelector("[data-search-link]").href = "search.html";
  }

  function init() {
    ui.mountHeader("detail");
    var ids = data.documents.map(function (doc) { return doc.id; });
    var currentId = ui.params().get("doc") || ids[0];
    var doc = data.getDocument(currentId);
    render(doc);

    document.querySelector("[data-next-doc]").addEventListener("click", function () {
      var index = ids.indexOf(doc.id);
      var nextId = ids[(index + 1) % ids.length];
      window.location.search = "?doc=" + encodeURIComponent(nextId);
    });
  }

  init();
})();
