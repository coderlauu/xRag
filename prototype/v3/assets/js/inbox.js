(function () {
  var data = window.XRagPrototypeV3;
  var ui = window.XRagPrototypeUI;

  function mountMetrics() {
    document.querySelector("[data-metrics]").innerHTML = ui.metricCards(data.metrics());
  }

  function renderScenarioList(currentId) {
    var host = document.querySelector("[data-scenario-list]");
    host.innerHTML = data.documents.map(function (doc) {
      var cls = doc.id === currentId ? "scenario-card is-active" : "scenario-card";
      var sourceLabel = doc.source_type === "link" ? "链接导入" : (doc.upload_mode === "multipart" ? "分片上传" : "单对象上传");
      return (
        '<button class="' + cls + '" type="button" data-scenario="' + ui.escapeHtml(doc.id) + '">' +
        "<strong>" + ui.escapeHtml(doc.title) + "</strong>" +
        '<span>' + ui.escapeHtml(sourceLabel + " · " + doc.size_label) + "</span>" +
        '<div class="status-row">' + ui.statusPill(doc.upload_status) + ui.statusPill(doc.parse_status) + "</div>" +
        "</button>"
      );
    }).join("");

    Array.prototype.forEach.call(host.querySelectorAll("[data-scenario]"), function (button) {
      button.addEventListener("click", function () {
        window.location.search = "?scenario=" + encodeURIComponent(button.getAttribute("data-scenario"));
      });
    });
  }

  function renderCurrent(doc) {
    var sourceMeta = doc.source_type === "link" ? ("链接来源：" + doc.source_url) : ("文件：" + doc.file_name);
    document.querySelector("[data-session-summary]").innerHTML =
      '<div class="callout">' +
      '<div class="status-row">' + ui.statusPill(doc.upload_status) + ui.statusPill(doc.parse_status) + "</div>" +
      '<h4 class="section-title">' + ui.escapeHtml(doc.title) + "</h4>" +
      '<p class="muted-copy">' + ui.escapeHtml(sourceMeta) + "</p>" +
      '<p class="muted-copy">' + ui.escapeHtml(doc.diagnosis_summary) + "</p>" +
      '<div class="meta-pair"><span>来源类型</span><strong>' + ui.escapeHtml(doc.source_type === "link" ? "网页链接" : "PDF 文件") + "</strong></div>" +
      '<div class="meta-pair"><span>关键解释</span><strong>' + ui.escapeHtml(doc.match_explanation || "等待生成匹配解释") + "</strong></div>" +
      "</div>";

    document.querySelector("[data-session-steps]").innerHTML = ui.timelineMarkup(doc.timeline);

    document.querySelector("[data-diagnosis-panel]").innerHTML =
      '<div class="diagnosis-card">' +
      '<h4>' + ui.escapeHtml(doc.diagnosis_code || "当前无失败诊断") + "</h4>" +
      '<p class="list-copy">' + ui.escapeHtml(doc.diagnosis_summary) + "</p>" +
      '<div class="button-row">' +
      '<a class="secondary-button" href="detail.html?doc=' + encodeURIComponent(doc.id) + '">查看文档时间线</a>' +
      '<a class="ghost-button" href="ops.html">查看运维证据</a>' +
      "</div>" +
      "</div>";
  }

  function renderQueue() {
    var items = [
      { title: "OCR 队列", summary: "当前 backlog 3，平均等待 4 分钟。", status: "processing" },
      { title: "链接抓取队列", summary: "失败站点按域名归类，支持后续限流与白名单。", status: "warning" },
      { title: "搜索投影", summary: "匹配解释与排序分数在解析成功后异步补齐。", status: "success" }
    ];

    document.querySelector("[data-queue-summary]").innerHTML =
      '<div class="stack-list">' +
      items.map(function (item) {
        return (
          '<div class="stack-list__item">' +
          '<div><strong>' + ui.escapeHtml(item.title) + '</strong><p class="muted-copy">' + ui.escapeHtml(item.summary) + "</p></div>" +
          '<div class="status-row">' + ui.statusPill(item.status) + "</div>" +
          "</div>"
        );
      }).join("") +
      "</div>";
  }

  function renderRecent() {
    document.querySelector("[data-recent-docs]").innerHTML = data.documents.map(function (doc) {
      return ui.docCard(doc);
    }).join("");
  }

  function init() {
    ui.mountHeader("inbox");
    var scenario = ui.params().get("scenario") || data.documents[0].id;
    var doc = data.getDocument(scenario);

    mountMetrics();
    renderScenarioList(doc.id);
    renderCurrent(doc);
    renderQueue();
    renderRecent();
  }

  init();
})();
