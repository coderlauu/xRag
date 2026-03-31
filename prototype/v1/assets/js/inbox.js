(function () {
  function byId(id) {
    return document.getElementById(id);
  }

  function renderMetrics() {
    var summary = window.XRagStore.getSummaries();
    byId("metric-total").textContent = summary.total;
    byId("metric-success").textContent = summary.success;
    byId("metric-processing").textContent = summary.processing;
    byId("metric-failed").textContent = summary.failed;
  }

  function renderRecent() {
    var filter = byId("recent-status-filter").value;
    var host = byId("recent-imports");
    var documents = window.XRagStore
      .getDocuments()
      .filter(function (doc) {
        return filter === "all" ? true : doc.parse_status === filter;
      })
      .sort(function (left, right) {
        return Date.parse(right.imported_at) - Date.parse(left.imported_at);
      })
      .slice(0, 8);

    if (!documents.length) {
      host.innerHTML =
        '<div class="empty-panel"><h3>还没有符合条件的导入记录</h3><p>先保存一段文本或上传一个文件，我们会把它加入最近导入区。</p></div>';
      return;
    }

    host.innerHTML = documents
      .map(function (doc) {
        return (
          '<article class="list-card">' +
          '<div class="list-card__main">' +
          '<div class="list-card__meta">' +
          window.XRagUI.renderStatusPill(doc.parse_status) +
          '<span class="meta-dot"></span>' +
          '<span>' +
          window.XRagUI.escapeHtml(window.XRagUI.sourceLabel(doc.source_type)) +
          "</span>" +
          '<span class="meta-dot"></span>' +
          '<span>' +
          window.XRagUI.escapeHtml(window.XRagUI.relativeTime(doc.imported_at)) +
          "</span>" +
          "</div>" +
          "<h3>" +
          window.XRagUI.escapeHtml(doc.title) +
          "</h3>" +
          "<p>" +
          window.XRagUI.escapeHtml(doc.content_preview || "暂无预览") +
          "</p>" +
          '<div class="tag-row">' +
          window.XRagUI.renderTagList(doc.tags) +
          "</div>" +
          "</div>" +
          '<div class="list-card__actions">' +
          '<a class="secondary-button" href="detail.html?id=' +
          encodeURIComponent(doc.id) +
          '">查看详情</a>' +
          '<a class="ghost-button" href="search.html?q=' +
          encodeURIComponent(doc.title.slice(0, 12)) +
          '">去搜索</a>' +
          "</div>" +
          "</article>"
        );
      })
      .join("");
  }

  function renderQueue() {
    var host = byId("pipeline-list");
    var documents = window.XRagStore
      .getDocuments()
      .filter(function (doc) {
        return doc.parse_status === "processing" || doc.parse_status === "failed";
      })
      .sort(function (left, right) {
        return Date.parse(right.imported_at) - Date.parse(left.imported_at);
      })
      .slice(0, 4);

    if (!documents.length) {
      host.innerHTML = '<li class="pipeline-item"><span class="muted">当前没有异常或处理中任务。</span></li>';
      return;
    }

    host.innerHTML = documents
      .map(function (doc) {
        var description =
          doc.parse_status === "failed"
            ? doc.parse_error_message || "解析失败"
            : "正在抽取正文并建立索引，稍后会自动变为可搜索状态。";

        return (
          '<li class="pipeline-item">' +
          '<div>' +
          "<strong>" +
          window.XRagUI.escapeHtml(doc.title) +
          "</strong>" +
          "<p>" +
          window.XRagUI.escapeHtml(description) +
          "</p>" +
          "</div>" +
          window.XRagUI.renderStatusPill(doc.parse_status) +
          "</li>"
        );
      })
      .join("");
  }

  function refresh() {
    window.XRagStore.tickProcessing();
    renderMetrics();
    renderRecent();
    renderQueue();
  }

  function handleManualSubmit(event) {
    event.preventDefault();
    var title = byId("manual-title").value;
    var content = byId("manual-content").value.trim();
    var tags = window.XRagUI.parseTags(byId("manual-tags").value);

    if (!content) {
      window.XRagStore.setFlashMessage({
        type: "error",
        title: "内容不能为空",
        message: "至少输入一段文本后才能保存。"
      });
      window.location.reload();
      return;
    }

    var doc = window.XRagStore.createManualDocument({
      title: title,
      content: content,
      tags: tags
    });

    window.XRagStore.setFlashMessage({
      type: "success",
      title: "文本已收进收件箱",
      message: "《" + doc.title + "》已经可以在搜索页里被检索。"
    });

    byId("manual-form").reset();
    refresh();
    window.XRagUI.mountToast();
  }

  function readFile(file) {
    return new Promise(function (resolve) {
      var extension = (file.name.split(".").pop() || "").toLowerCase();
      if (["txt", "md"].indexOf(extension) >= 0) {
        var reader = new FileReader();
        reader.onload = function () {
          resolve(String(reader.result || ""));
        };
        reader.onerror = function () {
          resolve("");
        };
        reader.readAsText(file);
      } else {
        resolve("");
      }
    });
  }

  async function handleFileUpload(event) {
    var files = Array.prototype.slice.call(event.target.files || []);
    if (!files.length) {
      return;
    }

    for (var index = 0; index < files.length; index += 1) {
      var file = files[index];
      var content = await readFile(file);
      window.XRagStore.ingestFilePayload({
        file_name: file.name,
        mime_type: file.type,
        content_raw: content,
        tags: []
      });
    }

    window.XRagStore.setFlashMessage({
      type: "success",
      title: "文件已加入导入队列",
      message: "支持的文件会自动进入解析流程，失败项会在列表里显示原因。"
    });

    event.target.value = "";
    refresh();
    window.XRagUI.mountToast();
  }

  function bindActions() {
    byId("manual-form").addEventListener("submit", handleManualSubmit);
    byId("file-input").addEventListener("change", handleFileUpload);
    byId("recent-status-filter").addEventListener("change", renderRecent);

    byId("demo-batch-button").addEventListener("click", function () {
      window.XRagStore.seedDemoBatch();
      window.XRagStore.setFlashMessage({
        type: "info",
        title: "已注入演示数据",
        message: "新增了一条成功记录和一条 OCR 失败记录，方便演示状态流。"
      });
      refresh();
      window.XRagUI.mountToast();
    });

    byId("reset-button").addEventListener("click", function () {
      window.XRagStore.reset();
      window.XRagStore.setFlashMessage({
        type: "info",
        title: "原型数据已重置",
        message: "已恢复到内置种子数据。"
      });
      window.location.reload();
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    window.XRagUI.mountHeader("inbox");
    window.XRagUI.mountToast();
    bindActions();
    refresh();
    window.setInterval(refresh, 2500);
  });
})();

