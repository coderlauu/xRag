(function () {
  var data = window.XRagPrototypeV4;
  var ui = window.XRagPrototypeUI;

  function render(doc) {
    var meta = doc.source_type === "link"
      ? (doc.source_url + " · " + doc.imported_at)
      : (doc.file_name + " · " + doc.size_label + " · " + doc.imported_at);

    document.querySelector("[data-detail-header]").innerHTML =
      '<div class="detail-header">' +
      '<div class="status-row">' + ui.statusPill(doc.parse_status) + ui.statusPill(doc.index_status) + "</div>" +
      '<h2 class="detail-title">' + ui.escapeHtml(doc.title) + "</h2>" +
      '<p class="muted-copy">' + ui.escapeHtml(meta) + "</p>" +
      '<div class="tag-row">' + ui.tagsMarkup(doc.tags) + "</div>" +
      "</div>";

    document.querySelector("[data-detail-content]").innerHTML =
      '<div class="stack-list">' +
      doc.chunks.map(function (chunk) {
        return '<div class="stack-list__item"><span>' + ui.escapeHtml(chunk.label) + '</span><strong>' + ui.escapeHtml(chunk.quote + " · " + chunk.locator) + "</strong></div>";
      }).join("") +
      "</div>";

    document.querySelector("[data-detail-timeline]").innerHTML = ui.timelineMarkup(doc.timeline);

    document.querySelector("[data-detail-upload]").innerHTML =
      '<div class="stack-list">' +
      '<div class="stack-list__item"><span>接入模式</span><strong>' + ui.escapeHtml(doc.source_type === "link" ? "链接导入" : "文件 / 文本导入") + "</strong></div>" +
      '<div class="stack-list__item"><span>解析状态</span><strong>' + ui.escapeHtml(doc.parse_status) + "</strong></div>" +
      '<div class="stack-list__item"><span>索引状态</span><strong>' + ui.escapeHtml(doc.index_status) + "</strong></div>" +
      '<div class="stack-list__item"><span>Freshness</span><strong>' + ui.escapeHtml(doc.freshness_summary) + "</strong></div>" +
      "</div>";

    document.querySelector("[data-detail-diagnosis]").innerHTML =
      '<div class="callout">' +
      '<h4>' + ui.escapeHtml(doc.diagnosis_code || "当前无失败诊断") + "</h4>" +
      '<p class="list-copy">' + ui.escapeHtml(doc.diagnosis_summary) + "</p>" +
      '<p class="list-copy">' + ui.escapeHtml(doc.retrieval_hint) + "</p>" +
      "</div>";

    document.querySelector("[data-detail-incident]").innerHTML =
      doc.cited_by.length
        ? '<div class="stack-list">' + doc.cited_by.map(function (sessionId) {
          var session = data.getSession(sessionId);
          return '<div class="stack-list__item"><span>引用会话</span><strong><a href="search.html?session=' + encodeURIComponent(session.id) + '">' + ui.escapeHtml(session.question) + '</a></strong></div>';
        }).join("") + "</div>"
        : '<p class="muted-copy">当前还没有答案引用这篇文档，可能是因为 scope 不匹配或索引 stale。</p>';

    document.querySelector("[data-search-link]").href = "search.html?session=sess-priority-answer";
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
