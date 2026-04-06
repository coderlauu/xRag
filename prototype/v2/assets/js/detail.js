(function () {
  var data = window.XRagPrototypeV2;
  var ui = window.XRagPrototypeUI;

  function render(doc) {
    document.querySelector("[data-detail-header]").innerHTML =
      '<div class="detail-header">' +
      '<div class="status-row">' + ui.statusPill(doc.upload_status) + ui.statusPill(doc.parse_status) + "</div>" +
      '<h2 class="detail-title">' + ui.escapeHtml(doc.title) + "</h2>" +
      '<p class="muted-copy">' + ui.escapeHtml(doc.file_name) + " · " + ui.escapeHtml(doc.size_label) + " · " + ui.escapeHtml(doc.imported_at) + "</p>" +
      '<div class="tag-row">' + ui.tagsMarkup(doc.tags) + "</div>" +
      "</div>";

    document.querySelector("[data-detail-content]").innerHTML =
      '<p>' + ui.escapeHtml(doc.content_clean || doc.content_preview) + "</p>";

    document.querySelector("[data-detail-timeline]").innerHTML = ui.timelineMarkup(doc.timeline);

    document.querySelector("[data-detail-upload]").innerHTML =
      '<div class="stack-list">' +
      '<div class="stack-list__item"><span>上传模式</span><strong>' + ui.escapeHtml(doc.upload_mode === "multipart" ? "分片上传" : "单对象上传") + "</strong></div>" +
      '<div class="stack-list__item"><span>上传状态</span><strong>' + ui.escapeHtml(doc.upload_status) + "</strong></div>" +
      '<div class="stack-list__item"><span>解析状态</span><strong>' + ui.escapeHtml(doc.parse_status) + "</strong></div>" +
      "</div>";

    document.querySelector("[data-detail-diagnosis]").innerHTML =
      '<div class="callout">' +
      '<h4>' + ui.escapeHtml(doc.diagnosis_code || "暂无诊断码") + "</h4>" +
      '<p class="list-copy">' + ui.escapeHtml(doc.diagnosis_summary) + "</p>" +
      "</div>";

    document.querySelector("[data-detail-incident]").innerHTML =
      doc.incident_ref
        ? '<div class="stack-list"><div class="stack-list__item"><span>关联事件</span><strong>' + ui.escapeHtml(doc.incident_ref) + '</strong></div><div class="stack-list__item"><span>推荐动作</span><strong>查看运维看板 / 执行重试动作</strong></div></div>'
        : '<p class="muted-copy">当前没有关联事件，可直接进入搜索页验证检索结果。</p>';

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
