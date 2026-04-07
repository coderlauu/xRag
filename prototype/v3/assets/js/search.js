(function () {
  var data = window.XRagPrototypeV3;
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
      return ui.docCard(doc, doc.match_explanation || doc.diagnosis_summary);
    }).join("");

    var ocr = results.filter(function (doc) { return doc.tags.indexOf("OCR") !== -1; }).length;
    var links = results.filter(function (doc) { return doc.source_type === "link"; }).length;
    var failed = results.filter(function (doc) { return doc.parse_status === "failed"; }).length;

    document.querySelector("[data-search-insights]").innerHTML =
      '<div class="stack-list">' +
      '<div class="stack-list__item"><span>OCR 结果</span><strong>' + ocr + "</strong></div>" +
      '<div class="stack-list__item"><span>链接结果</span><strong>' + links + "</strong></div>" +
      '<div class="stack-list__item"><span>失败待处理</span><strong>' + failed + "</strong></div>" +
      '<div class="stack-list__item"><span>推荐动作</span><strong>' + (failed ? "先处理反爬 / OCR backlog，再看排序结果" : "可以继续评审匹配解释和排序") + "</strong></div>" +
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
