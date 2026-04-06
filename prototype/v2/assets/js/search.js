(function () {
  var data = window.XRagPrototypeV2;
  var ui = window.XRagPrototypeUI;

  function diagnosisOptions() {
    var seen = {};
    return data.documents
      .map(function (doc) { return doc.diagnosis_code; })
      .filter(Boolean)
      .filter(function (code) {
        if (seen[code]) {
          return false;
        }
        seen[code] = true;
        return true;
      });
  }

  function mountDiagnosisSelect() {
    var host = document.querySelector("#filter-diagnosis");
    host.innerHTML += diagnosisOptions().map(function (code) {
      return '<option value="' + ui.escapeHtml(code) + '">' + ui.escapeHtml(code) + "</option>";
    }).join("");
  }

  function renderResults(filters) {
    var results = data.queryDocs(filters);
    document.querySelector("[data-result-count]").textContent = "当前命中 " + results.length + " 条";
    document.querySelector("[data-search-results]").innerHTML = results.map(function (doc) {
      return ui.docCard(doc, doc.diagnosis_summary);
    }).join("");

    var failed = results.filter(function (doc) { return doc.parse_status === "failed"; }).length;
    var processing = results.filter(function (doc) { return doc.parse_status === "processing" || doc.upload_status === "uploading"; }).length;

    document.querySelector("[data-search-insights]").innerHTML =
      '<div class="stack-list">' +
      '<div class="stack-list__item"><span>失败结果</span><strong>' + failed + "</strong></div>" +
      '<div class="stack-list__item"><span>处理中</span><strong>' + processing + "</strong></div>" +
      '<div class="stack-list__item"><span>建议动作</span><strong>' + (failed ? "先修对象或解析错误" : "可继续联调 API") + "</strong></div>" +
      "</div>";
  }

  function currentFilters() {
    return {
      query: document.querySelector("#search-input").value,
      status: document.querySelector("#filter-status").value,
      source: document.querySelector("#filter-source").value,
      diagnosis: document.querySelector("#filter-diagnosis").value
    };
  }

  function init() {
    ui.mountHeader("search");
    mountDiagnosisSelect();
    renderResults({ query: "", status: "all", source: "all", diagnosis: "all" });

    document.querySelector("#search-form").addEventListener("submit", function (event) {
      event.preventDefault();
      renderResults(currentFilters());
    });
  }

  init();
})();
