(function () {
  var data = window.XRagPrototypeV2;
  var ui = window.XRagPrototypeUI;

  function mountMetrics() {
    document.querySelector("[data-metrics]").innerHTML = ui.metricCards(data.metrics());
  }

  function renderScenarioList(currentId) {
    var host = document.querySelector("[data-scenario-list]");
    host.innerHTML = data.documents.map(function (doc) {
      var cls = doc.id === currentId ? "scenario-card is-active" : "scenario-card";
      return (
        '<button class="' + cls + '" type="button" data-scenario="' + ui.escapeHtml(doc.id) + '">' +
        "<strong>" + ui.escapeHtml(doc.title) + "</strong>" +
        '<span>' + ui.escapeHtml((doc.upload_mode === "multipart" ? "分片上传" : "单对象上传") + " · " + doc.size_label) + "</span>" +
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
    document.querySelector("[data-session-summary]").innerHTML =
      '<div class="callout">' +
      '<div class="status-row">' + ui.statusPill(doc.upload_status) + ui.statusPill(doc.parse_status) + "</div>" +
      '<h4 class="section-title">' + ui.escapeHtml(doc.file_name) + "</h4>" +
      '<p class="muted-copy">' + ui.escapeHtml(doc.diagnosis_summary) + "</p>" +
      '<div class="meta-pair"><span>模式</span><strong>' + ui.escapeHtml(doc.upload_mode === "multipart" ? "分片上传" : "单对象上传") + "</strong></div>" +
      '<div class="meta-pair"><span>大小</span><strong>' + ui.escapeHtml(doc.size_label) + "</strong></div>" +
      "</div>";

    document.querySelector("[data-session-steps]").innerHTML = ui.timelineMarkup(doc.timeline);

    document.querySelector("[data-diagnosis-panel]").innerHTML =
      '<div class="diagnosis-card">' +
      '<h4>' + ui.escapeHtml(doc.diagnosis_code || "暂无诊断码") + "</h4>" +
      '<p class="list-copy">' + ui.escapeHtml(doc.diagnosis_summary) + "</p>" +
      '<div class="button-row">' +
      '<a class="secondary-button" href="detail.html?doc=' + encodeURIComponent(doc.id) + '">查看详情</a>' +
      '<a class="ghost-button" href="ops.html">打开 runbook</a>' +
      "</div>" +
      "</div>";
  }

  function renderQueue() {
    var processingDocs = data.documents.filter(function (doc) {
      return doc.upload_status === "uploading" || doc.parse_status === "processing" || doc.parse_status === "pending" || doc.parse_status === "failed";
    });

    document.querySelector("[data-queue-summary]").innerHTML =
      '<div class="stack-list">' +
      processingDocs.map(function (doc) {
        return (
          '<div class="stack-list__item">' +
          '<div><strong>' + ui.escapeHtml(doc.title) + "</strong><p class=\"muted-copy\">" + ui.escapeHtml(doc.diagnosis_summary) + "</p></div>" +
          '<div class="status-row">' + ui.statusPill(doc.upload_status) + ui.statusPill(doc.parse_status) + "</div>" +
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
