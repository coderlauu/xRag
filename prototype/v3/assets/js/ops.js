(function () {
  var data = window.XRagPrototypeV3;
  var ui = window.XRagPrototypeUI;

  function mountMetrics() {
    var items = [
      { label: "健康服务", value: String(data.services.filter(function (item) { return item.status === "healthy"; }).length), hint: "当前通过健康检查的服务" },
      { label: "待处理事件", value: String(data.incidents.filter(function (item) { return item.status !== "resolved"; }).length), hint: "来自 OCR、抓取器与 CI 的当前事件" },
      { label: "OCR 积压", value: "3", hint: "等待 OCR 的扫描件任务数" },
      { label: "回滚基线", value: data.rollback.previous_tag, hint: "上一稳定版本 tag" }
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
      "当 OCR backlog 升高时，优先扩 worker 或限制单批次扫描页数，而不是直接回滚整个解析链。",
      "当链接抓取失败时，应先区分网络错误、站点反爬和正文提取失败三种诊断。",
      "当搜索排序异常时，优先检查匹配解释和投影字段是否同步刷新。"
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
