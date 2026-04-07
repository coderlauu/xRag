(function () {
  var data = window.XRagPrototypeV4;
  var ui = window.XRagPrototypeUI;

  function mountMetrics() {
    var items = [
      { label: "Recall@10", value: data.evaluation.recall_at_10, hint: "retrieval 基线指标" },
      { label: "Groundedness", value: data.evaluation.groundedness, hint: "答案 claim 被引用支持的程度" },
      { label: "Citation Coverage", value: data.evaluation.citation_coverage, hint: "关键 claim 有 citation 的比例" },
      { label: "p95 Latency", value: data.evaluation.latency_p95, hint: "从提问到返回答案的延迟" }
    ];

    document.querySelector("[data-ops-metrics]").innerHTML = ui.metricCards(items);
  }

  function mountServices() {
    document.querySelector("[data-service-health]").innerHTML = data.services.map(function (service) {
      return (
        '<article class="service-card">' +
        '<div class="status-row">' + ui.statusPill(service.status) + "</div>" +
        '<h4>' + ui.escapeHtml(service.name) + "</h4>" +
        '<p class="muted-copy">' + ui.escapeHtml(service.detail) + "</p>" +
        "</article>"
      );
    }).join("");
  }

  function mountRollback() {
    document.querySelector("[data-rollback-panel]").innerHTML =
      '<div class="callout">' +
      '<div class="meta-pair"><span>当前 tag</span><strong>' + ui.escapeHtml(data.rollback.current_tag) + "</strong></div>" +
      '<div class="meta-pair"><span>上个稳定 tag</span><strong>' + ui.escapeHtml(data.rollback.previous_tag) + "</strong></div>" +
      '<div class="meta-pair"><span>时间窗</span><strong>' + ui.escapeHtml(data.rollback.deploy_window) + "</strong></div>" +
      '<ol class="number-list">' +
      data.rollback.steps.map(function (step) { return "<li>" + ui.escapeHtml(step) + "</li>"; }).join("") +
      "</ol>" +
      "</div>";
  }

  function mountIncidents() {
    document.querySelector("[data-incident-list]").innerHTML = data.incidents.map(ui.incidentCard).join("");
  }

  function mountActions() {
    var actions = [
      "当 groundedness 或 citation coverage 下滑时，先关闭 answer generation，只保留 retrieval trace。",
      "当 freshness lag 上升时，优先排查 embedding backlog 与 provider latency，而不是先怀疑前端问答页。",
      "当拒答率升高时，要先区分 evidence 不足、scope 过大和 provider 失败三类原因。"
    ];
    document.querySelector("[data-ops-actions]").innerHTML = actions.map(function (item) {
      return "<li>" + ui.escapeHtml(item) + "</li>";
    }).join("");
  }

  function init() {
    ui.mountHeader("ops");
    mountMetrics();
    mountServices();
    mountRollback();
    mountIncidents();
    mountActions();
  }

  init();
})();
