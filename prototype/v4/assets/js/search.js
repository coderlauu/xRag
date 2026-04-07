(function () {
  var data = window.XRagPrototypeV4;
  var ui = window.XRagPrototypeUI;

  function currentSession() {
    return data.getSession(ui.params().get("session"));
  }

  function renderResults(filters) {
    var session = currentSession();
    var results = data.queryHits(session.id, filters);
    document.querySelector("[data-result-count]").textContent = "当前 trace 中 " + results.length + " 条";
    document.querySelector("[data-search-results]").innerHTML = results.map(function (item) {
      var doc = item.document;
      var hit = item.hit;
      return (
        '<article class="list-card">' +
        '<div class="list-card__head">' +
        '<div><h4 class="list-title"><a href="detail.html?doc=' + encodeURIComponent(doc.id) + '">' + ui.escapeHtml(doc.title) + '</a></h4>' +
        '<p class="muted-copy">' + ui.escapeHtml((doc.source_url || doc.file_name || doc.source_type) + " · " + doc.freshness_summary) + '</p></div>' +
        '<div class="status-row">' + ui.statusPill(doc.index_status) + (hit.used_in_answer ? ui.statusPill("answered") : "") + "</div>" +
        "</div>" +
        '<p class="list-copy">' + ui.escapeHtml(hit.explanation) + "</p>" +
        '<div class="stack-list">' +
        '<div class="stack-list__item"><span>Lexical</span><strong>' + ui.escapeHtml(String(hit.lexical_score)) + '</strong></div>' +
        '<div class="stack-list__item"><span>Semantic</span><strong>' + ui.escapeHtml(String(hit.semantic_score)) + '</strong></div>' +
        '<div class="stack-list__item"><span>Rerank</span><strong>' + ui.escapeHtml(String(hit.rerank_score)) + '</strong></div>' +
        "</div>" +
        "</article>"
      );
    }).join("");

    var used = results.filter(function (item) { return item.hit.used_in_answer; }).length;
    var stale = results.filter(function (item) { return item.document.index_status === "stale"; }).length;

    document.querySelector("[data-search-insights]").innerHTML =
      '<div class="stack-list">' +
      '<div class="stack-list__item"><span>当前问题</span><strong>' + ui.escapeHtml(session.question) + "</strong></div>" +
      '<div class="stack-list__item"><span>Scope</span><strong>' + ui.escapeHtml(session.scope_summary) + "</strong></div>" +
      '<div class="stack-list__item"><span>进入答案的 hits</span><strong>' + used + "</strong></div>" +
      '<div class="stack-list__item"><span>Stale 命中</span><strong>' + stale + "</strong></div>" +
      '<div class="stack-list__item"><span>推荐动作</span><strong>' + ui.escapeHtml(stale ? "先重建 stale 文档索引，再决定是否生成答案" : "可以继续评审 answer pack 与 citation") + "</strong></div>" +
      "</div>";
  }

  function currentFilters() {
    return {
      query: document.querySelector("#search-input").value,
      mode: document.querySelector("#filter-mode").value,
      source: document.querySelector("#filter-source").value,
      index_status: document.querySelector("#filter-index-status").value
    };
  }

  function init() {
    ui.mountHeader("search");
    var session = currentSession();
    document.querySelector("#search-input").value = session.question;
    document.querySelector("#filter-mode").value = session.retrieval_mode;
    renderResults({ query: session.question, mode: "all", source: "all", index_status: "all" });

    document.querySelector("#search-form").addEventListener("submit", function (event) {
      event.preventDefault();
      renderResults(currentFilters());
    });
  }

  init();
})();
