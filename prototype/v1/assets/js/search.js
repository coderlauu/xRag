(function () {
  function byId(id) {
    return document.getElementById(id);
  }

  function readFilters() {
    return {
      source: byId("filter-source").value,
      status: byId("filter-status").value,
      tag: byId("filter-tag").value,
      timeRange: byId("filter-time").value
    };
  }

  function fillTagOptions() {
    var select = byId("filter-tag");
    var tags = window.XRagStore.getAvailableTags();
    select.innerHTML =
      '<option value="all">全部标签</option>' +
      tags
        .map(function (tag) {
          return '<option value="' + window.XRagUI.escapeHtml(tag) + '">' + window.XRagUI.escapeHtml(tag) + "</option>";
        })
        .join("");
  }

  function renderInsights(results) {
    var summary = window.XRagStore.getSummaries();
    byId("insight-total").textContent = results.length;
    byId("insight-success").textContent = summary.success;
    byId("insight-processing").textContent = summary.processing;

    var topTags = Object.entries(summary.tags)
      .sort(function (left, right) {
        return right[1] - left[1];
      })
      .slice(0, 5);

    byId("top-tags").innerHTML = topTags.length
      ? topTags
          .map(function (entry) {
            return '<li><span>' + window.XRagUI.escapeHtml(entry[0]) + "</span><strong>" + entry[1] + "</strong></li>";
          })
          .join("")
      : '<li><span class="muted">暂无标签统计</span></li>';
  }

  function renderResults() {
    var query = byId("search-input").value.trim();
    var filters = readFilters();
    var host = byId("results");
    var results = window.XRagStore.searchDocuments(query, filters);

    window.XRagUI.updateQuery({
      q: query,
      source: filters.source,
      status: filters.status,
      tag: filters.tag,
      time: filters.timeRange
    });

    byId("results-count").textContent = results.length;
    renderInsights(results);

    if (!results.length) {
      host.innerHTML =
        '<div class="empty-panel search-empty">' +
        "<h3>没有找到匹配内容</h3>" +
        "<p>可以换一个关键词，或尝试通过标签、来源类型和时间范围缩小范围。</p>" +
        '<div class="suggestion-row">' +
        '<button class="ghost-button" data-suggest="关键词">试试“关键词”</button>' +
        '<button class="ghost-button" data-suggest="用户研究">试试“用户研究”</button>' +
        '<button class="ghost-button" data-suggest="OCR">试试“OCR”</button>' +
        "</div>" +
        "</div>";

      Array.prototype.forEach.call(host.querySelectorAll("[data-suggest]"), function (button) {
        button.addEventListener("click", function () {
          byId("search-input").value = button.getAttribute("data-suggest");
          renderResults();
        });
      });
      return;
    }

    host.innerHTML = results
      .map(function (result) {
        var doc = result.document;
        return (
          '<article class="search-card">' +
          '<div class="search-card__header">' +
          '<div class="list-card__meta">' +
          window.XRagUI.renderStatusPill(doc.parse_status) +
          '<span class="meta-dot"></span>' +
          "<span>" +
          window.XRagUI.escapeHtml(window.XRagUI.sourceLabel(doc.source_type)) +
          "</span>" +
          '<span class="meta-dot"></span>' +
          "<span>" +
          window.XRagUI.escapeHtml(window.XRagUI.formatDate(doc.imported_at, true)) +
          "</span>" +
          "</div>" +
          '<a class="primary-link" href="detail.html?id=' +
          encodeURIComponent(doc.id) +
          '">' +
          window.XRagUI.escapeHtml(doc.title) +
          "</a>" +
          "</div>" +
          '<p class="search-snippet">' +
          window.XRagUI.highlight(result.snippet, query) +
          "</p>" +
          '<div class="search-card__footer">' +
          '<div class="tag-row">' +
          window.XRagUI.renderTagList(doc.tags) +
          "</div>" +
          '<div class="meta-inline">' +
          (doc.file_name ? "<span>" + window.XRagUI.escapeHtml(doc.file_name) + "</span>" : "") +
          (doc.source_url ? "<span>" + window.XRagUI.escapeHtml(doc.source_url) + "</span>" : "") +
          "</div>" +
          "</div>" +
          "</article>"
        );
      })
      .join("");
  }

  function applyQueryParams() {
    var params = window.XRagUI.queryParams();
    byId("search-input").value = params.get("q") || "";
    byId("filter-source").value = params.get("source") || "all";
    byId("filter-status").value = params.get("status") || "all";
    byId("filter-time").value = params.get("time") || "all";
  }

  function bindEvents() {
    byId("search-form").addEventListener("submit", function (event) {
      event.preventDefault();
      renderResults();
    });

    ["filter-source", "filter-status", "filter-tag", "filter-time"].forEach(function (id) {
      byId(id).addEventListener("change", renderResults);
    });

    byId("clear-filters").addEventListener("click", function () {
      byId("filter-source").value = "all";
      byId("filter-status").value = "all";
      byId("filter-tag").value = "all";
      byId("filter-time").value = "all";
      renderResults();
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    window.XRagUI.mountHeader("search");
    fillTagOptions();
    applyQueryParams();
    var params = window.XRagUI.queryParams();
    if (params.get("tag")) {
      byId("filter-tag").value = params.get("tag");
    }
    bindEvents();
    renderResults();
  });
})();

