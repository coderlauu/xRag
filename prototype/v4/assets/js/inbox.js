(function () {
  var data = window.XRagPrototypeV4;
  var ui = window.XRagPrototypeUI;

  function mountMetrics() {
    document.querySelector("[data-metrics]").innerHTML = ui.metricCards(data.metrics());
  }

  function renderScenarioList(currentId) {
    var host = document.querySelector("[data-scenario-list]");
    host.innerHTML = data.sessions.map(function (session) {
      var cls = session.id === currentId ? "scenario-card is-active" : "scenario-card";
      return (
        '<button class="' + cls + '" type="button" data-scenario="' + ui.escapeHtml(session.id) + '">' +
        "<strong>" + ui.escapeHtml(session.question) + "</strong>" +
        '<span>' + ui.escapeHtml(session.scope_summary + " · " + session.retrieval_mode) + "</span>" +
        '<div class="status-row">' + ui.statusPill(session.answer_status) + "</div>" +
        "</button>"
      );
    }).join("");

    Array.prototype.forEach.call(host.querySelectorAll("[data-scenario]"), function (button) {
      button.addEventListener("click", function () {
        window.location.search = "?scenario=" + encodeURIComponent(button.getAttribute("data-scenario"));
      });
    });
  }

  function renderCurrent(session) {
    document.querySelector("[data-session-summary]").innerHTML =
      '<div class="callout">' +
      '<div class="status-row">' + ui.statusPill(session.answer_status) + "</div>" +
      '<h4 class="section-title">' + ui.escapeHtml(session.question) + "</h4>" +
      '<p class="muted-copy">' + ui.escapeHtml(session.scope_summary) + "</p>" +
      '<p class="muted-copy">' + ui.escapeHtml(session.answer_summary || session.refusal_reason) + "</p>" +
      '<div class="meta-pair"><span>Retrieval Mode</span><strong>' + ui.escapeHtml(session.retrieval_mode) + "</strong></div>" +
      '<div class="meta-pair"><span>Latency / Cost</span><strong>' + ui.escapeHtml(session.latency_ms + "ms / " + session.token_cost) + "</strong></div>" +
      "</div>";

    document.querySelector("[data-session-steps]").innerHTML = ui.timelineMarkup(session.steps);

    document.querySelector("[data-diagnosis-panel]").innerHTML =
      '<div class="diagnosis-card">' +
      '<h4>' + ui.escapeHtml(session.diagnosis_code || "当前可直接回答") + "</h4>" +
      '<p class="list-copy">' + ui.escapeHtml(session.refusal_reason || session.answer_summary) + "</p>" +
      '<div class="stack-list">' +
      session.citations.map(function (item) {
        return '<div class="stack-list__item"><span>引用</span><strong>' + ui.escapeHtml(data.getDocument(item.document_id).title + " · " + item.locator) + "</strong></div>";
      }).join("") +
      (session.citations.length ? "" : '<div class="stack-list__item"><span>推荐动作</span><strong>缩小 scope 或补充资料</strong></div>') +
      "</div>" +
      '<div class="button-row">' +
      '<a class="secondary-button" href="search.html?session=' + encodeURIComponent(session.id) + '">查看 retrieval trace</a>' +
      '<a class="ghost-button" href="ops.html">查看评估与运维板</a>' +
      "</div>" +
      "</div>";
  }

  function renderSignals() {
    var items = [
      { title: "Embedding Backlog", summary: "当前 backlog 4，stale 文档 1 篇。", status: "embedding" },
      { title: "Freshness 窗口", summary: "目标 5 分钟，当前 p95 已到 7 分钟。", status: "warning" },
      { title: "Refusal Policy", summary: "证据不足时优先拒答，而不是产出弱答案。", status: "ready" }
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
    document.querySelector("[data-recent-docs]").innerHTML = data.sessions.map(function (session) {
      var summary = session.answer_summary || session.refusal_reason;
      return (
        '<article class="list-card">' +
        '<div class="list-card__head">' +
        '<div><h4 class="list-title"><a href="search.html?session=' + encodeURIComponent(session.id) + '">' + ui.escapeHtml(session.question) + '</a></h4>' +
        '<p class="muted-copy">' + ui.escapeHtml(session.scope_summary) + '</p></div>' +
        '<div class="status-row">' + ui.statusPill(session.answer_status) + "</div>" +
        "</div>" +
        '<p class="list-copy">' + ui.escapeHtml(summary) + "</p>" +
        '<div class="tag-row">' + session.citations.map(function (item) {
          return '<span class="tag-chip">' + ui.escapeHtml(data.getDocument(item.document_id).title) + "</span>";
        }).join("") + "</div>" +
        "</article>"
      );
    }).join("");
  }

  function init() {
    ui.mountHeader("inbox");
    var scenario = ui.params().get("scenario") || data.sessions[0].id;
    var session = data.getSession(scenario);

    mountMetrics();
    renderScenarioList(session.id);
    renderCurrent(session);
    renderSignals();
    renderRecent();
  }

  init();
})();
