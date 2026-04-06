(function () {
  var data = window.XRagPrototypeV2;
  var ui = window.XRagPrototypeUI;

  function mountMetrics() {
    var items = [
      { label: "健康服务", value: String(data.services.filter(function (item) { return item.status === "healthy"; }).length), hint: "当前通过健康检查的服务" },
      { label: "告警项", value: String(data.services.filter(function (item) { return item.status === "warning"; }).length), hint: "需要关注但不一定阻断上线" },
      { label: "未关闭事件", value: String(data.incidents.filter(function (item) { return item.status !== "resolved"; }).length), hint: "来自上传链路或 CI 的当前事件" },
      { label: "回滚 Tag", value: data.rollback.previous_tag, hint: "上一个稳定镜像 tag" }
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
      '<div class="meta-pair"><span>窗口</span><strong>' + ui.escapeHtml(data.rollback.deploy_window) + "</strong></div>" +
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
      "当上传 complete 失败时，先查对象是否存在，再决定是否重试 multipart part。",
      "当 PDF 解析失败但对象已存在时，不要回到上传页，直接在详情页提供 parse retry。",
      "当部署失败时，优先看事件 artifact，再决定是否回滚镜像 tag。"
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
