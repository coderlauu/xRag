(function () {
  var STATUS_COPY = {
    pending: "待处理",
    processing: "处理中",
    success: "成功",
    failed: "失败"
  };

  var SOURCE_COPY = {
    text: "文本",
    file: "文件",
    pdf: "PDF",
    link: "链接"
  };

  function escapeHtml(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function parseTags(input) {
    return Array.from(
      new Set(
        String(input || "")
          .split(/[,，#\n]/)
          .map(function (item) {
            return item.trim();
          })
          .filter(Boolean)
      )
    );
  }

  function statusClass(status) {
    return "status-pill status-" + status;
  }

  function renderStatusPill(status) {
    return '<span class="' + statusClass(status) + '">' + escapeHtml(STATUS_COPY[status] || status) + "</span>";
  }

  function renderTag(tag) {
    return '<span class="tag-chip">' + escapeHtml(tag) + "</span>";
  }

  function renderTagList(tags) {
    if (!tags || !tags.length) {
      return '<span class="muted">暂无标签</span>';
    }
    return tags.map(renderTag).join("");
  }

  function sourceLabel(source) {
    return SOURCE_COPY[source] || source;
  }

  function formatDate(dateString, withTime) {
    if (!dateString) {
      return "未知时间";
    }

    var formatter = new Intl.DateTimeFormat("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: withTime ? "2-digit" : undefined,
      minute: withTime ? "2-digit" : undefined
    });

    return formatter.format(new Date(dateString));
  }

  function relativeTime(dateString) {
    if (!dateString) {
      return "";
    }
    var delta = Date.now() - Date.parse(dateString);
    var minutes = Math.round(delta / 60000);
    if (minutes < 1) {
      return "刚刚";
    }
    if (minutes < 60) {
      return minutes + " 分钟前";
    }
    var hours = Math.round(minutes / 60);
    if (hours < 24) {
      return hours + " 小时前";
    }
    var days = Math.round(hours / 24);
    return days + " 天前";
  }

  function highlight(text, query) {
    var safe = escapeHtml(text || "");
    if (!query) {
      return safe;
    }
    var pattern = query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return safe.replace(new RegExp("(" + pattern + ")", "ig"), "<mark>$1</mark>");
  }

  function consumeToast() {
    return window.XRagStore.consumeFlashMessage();
  }

  function mountToast() {
    var toast = consumeToast();
    var host = document.querySelector("[data-toast]");
    if (!host || !toast) {
      return;
    }

    host.innerHTML =
      '<div class="toast toast-' +
      escapeHtml(toast.type || "info") +
      '">' +
      '<strong>' +
      escapeHtml(toast.title || "提示") +
      "</strong>" +
      '<p>' +
      escapeHtml(toast.message || "") +
      "</p>" +
      "</div>";
  }

  function navMarkup(active) {
    var items = [
      { key: "inbox", href: "index.html", label: "导入页" },
      { key: "search", href: "search.html", label: "搜索页" },
      { key: "detail", href: "detail.html", label: "详情页" }
    ];

    return items
      .map(function (item) {
        var cls = item.key === active ? "nav-link is-active" : "nav-link";
        return '<a class="' + cls + '" href="' + item.href + '">' + escapeHtml(item.label) + "</a>";
      })
      .join("");
  }

  function mountHeader(pageKey) {
    var host = document.querySelector("[data-global-header]");
    if (!host) {
      return;
    }

    host.innerHTML =
      '<div class="brand-block">' +
      '<a class="brand-mark" href="index.html">x</a>' +
      '<div>' +
      '<p class="eyebrow">Personal Knowledge Inbox</p>' +
      '<h1>xRag Prototype v1</h1>' +
      "</div>" +
      "</div>" +
      '<nav class="main-nav">' +
      navMarkup(pageKey) +
      "</nav>" +
      '<div class="header-actions">' +
      '<a class="ghost-button" href="../README.md">版本说明</a>' +
      "</div>";
  }

  function queryParams() {
    return new URLSearchParams(window.location.search);
  }

  function updateQuery(next) {
    var url = new URL(window.location.href);
    Object.keys(next).forEach(function (key) {
      var value = next[key];
      if (value === null || value === undefined || value === "" || value === "all") {
        url.searchParams.delete(key);
      } else {
        url.searchParams.set(key, value);
      }
    });
    window.history.replaceState({}, "", url);
  }

  window.XRagUI = {
    escapeHtml: escapeHtml,
    formatDate: formatDate,
    highlight: highlight,
    mountHeader: mountHeader,
    mountToast: mountToast,
    parseTags: parseTags,
    queryParams: queryParams,
    relativeTime: relativeTime,
    renderStatusPill: renderStatusPill,
    renderTagList: renderTagList,
    sourceLabel: sourceLabel,
    updateQuery: updateQuery
  };
})();

