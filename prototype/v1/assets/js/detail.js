(function () {
  function byId(id) {
    return document.getElementById(id);
  }

  function getDocumentId() {
    return window.XRagUI.queryParams().get("id");
  }

  function relatedDocuments(current) {
    return window.XRagStore
      .getDocuments()
      .filter(function (doc) {
        if (doc.id === current.id) {
          return false;
        }
        return doc.tags.some(function (tag) {
          return current.tags.indexOf(tag) >= 0;
        });
      })
      .slice(0, 3);
  }

  function renderDocument(doc) {
    byId("detail-title").textContent = doc.title;
    byId("detail-status").innerHTML = window.XRagUI.renderStatusPill(doc.parse_status);
    byId("detail-source").textContent = window.XRagUI.sourceLabel(doc.source_type);
    byId("detail-imported").textContent = window.XRagUI.formatDate(doc.imported_at, true);
    byId("detail-origin").textContent = doc.source_origin || "manual";
    byId("detail-file").textContent = doc.file_name || "无";
    byId("detail-url").textContent = doc.source_url || "无";
    byId("detail-tags").innerHTML = window.XRagUI.renderTagList(doc.tags);
    byId("tag-input").value = doc.tags.join(", ");
    byId("detail-content").textContent =
      doc.parse_status === "failed"
        ? doc.parse_error_message || "解析失败，当前暂无正文。"
        : doc.content_clean || doc.content_raw || "暂无正文。";

    var timeline = [
      {
        label: "导入进入收件箱",
        description: "系统创建文档记录并开始管理元数据。",
        time: doc.imported_at
      },
      {
        label: doc.parse_status === "success" ? "已完成解析并建立索引" : "解析状态等待更新",
        description:
          doc.parse_status === "success"
            ? "当前文档已经可以在搜索页中按关键词找回。"
            : doc.parse_status === "processing"
              ? "正在抽取正文内容与命中摘要。"
              : "本次解析失败，可手动重试。"
      }
    ];

    byId("detail-timeline").innerHTML = timeline
      .map(function (item) {
        return (
          '<li class="timeline-item">' +
          "<strong>" +
          window.XRagUI.escapeHtml(item.label) +
          "</strong>" +
          "<p>" +
          window.XRagUI.escapeHtml(item.description) +
          "</p>" +
          (item.time ? "<span>" + window.XRagUI.escapeHtml(window.XRagUI.formatDate(item.time, true)) + "</span>" : "") +
          "</li>"
        );
      })
      .join("");

    byId("retry-button").hidden = doc.parse_status !== "failed";
    byId("processing-note").hidden = doc.parse_status !== "processing";

    var related = relatedDocuments(doc);
    byId("related-docs").innerHTML = related.length
      ? related
          .map(function (item) {
            return (
              '<a class="related-card" href="detail.html?id=' +
              encodeURIComponent(item.id) +
              '">' +
              "<strong>" +
              window.XRagUI.escapeHtml(item.title) +
              "</strong>" +
              "<p>" +
              window.XRagUI.escapeHtml(item.content_preview) +
              "</p>" +
              "</a>"
            );
          })
          .join("")
      : '<div class="empty-panel"><p>暂时没有更多相似资料。</p></div>';
  }

  function renderMissing() {
    byId("detail-page").innerHTML =
      '<section class="hero-card"><h2>没有找到这条文档</h2><p>它可能已经被重置，或者链接里的 id 不存在。</p><a class="primary-button" href="search.html">返回搜索页</a></section>';
  }

  function refresh() {
    var doc = window.XRagStore.getDocument(getDocumentId());
    if (!doc) {
      renderMissing();
      return;
    }
    renderDocument(doc);
  }

  function bindEvents() {
    byId("tag-form").addEventListener("submit", function (event) {
      event.preventDefault();
      var tags = window.XRagUI.parseTags(byId("tag-input").value);
      window.XRagStore.updateTags(getDocumentId(), tags);
      window.XRagStore.setFlashMessage({
        type: "success",
        title: "标签已更新",
        message: "新的标签已经写入当前原型数据。"
      });
      refresh();
      window.XRagUI.mountToast();
    });

    byId("retry-button").addEventListener("click", function () {
      window.XRagStore.retryDocument(getDocumentId());
      refresh();
    });

    byId("search-by-title").addEventListener("click", function () {
      var doc = window.XRagStore.getDocument(getDocumentId());
      if (!doc) {
        return;
      }
      window.location.href = "search.html?q=" + encodeURIComponent(doc.title.split(" ").slice(0, 2).join(" "));
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    window.XRagUI.mountHeader("detail");
    window.XRagUI.mountToast();
    bindEvents();
    refresh();
    window.setInterval(refresh, 2500);
  });
})();

