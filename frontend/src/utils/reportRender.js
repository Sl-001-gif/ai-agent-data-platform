/** 报告正文渲染：按标题分段，把图表挂到对应步骤段落后，迷你 Markdown 转 HTML。 */

export function escapeHtml(src) {
  return String(src == null ? "" : src)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** 行内：**粗体** / *斜体* / `代码`（先转义再替换）。 */
export function renderInline(src) {
  return escapeHtml(src)
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\*([^*]+)\*/g, "<em>$1</em>")
    .replace(/`([^`]+)`/g, "<code>$1</code>");
}

/** 块级：段落 / - 无序列表 / 1. 有序列表 / --- 分隔线。 */
export function renderMarkdown(src) {
  const lines = String(src == null ? "" : src).split(/\r?\n/);
  const html = [];
  let listType = null;
  const closeList = () => {
    if (listType) {
      html.push("</" + listType + ">");
      listType = null;
    }
  };
  for (const raw of lines) {
    const line = raw.trimEnd();
    if (!line.trim()) {
      closeList();
      continue;
    }
    if (/^\s*---+\s*$/.test(line)) {
      closeList();
      html.push("<hr>");
      continue;
    }
    const ul = line.match(/^\s*[-*]\s+(.*)$/);
    if (ul) {
      if (listType !== "ul") {
        closeList();
        html.push("<ul>");
        listType = "ul";
      }
      html.push("<li>" + renderInline(ul[1]) + "</li>");
      continue;
    }
    const ol = line.match(/^\s*\d+[.)]\s+(.*)$/);
    if (ol) {
      if (listType !== "ol") {
        closeList();
        html.push("<ol>");
        listType = "ol";
      }
      html.push("<li>" + renderInline(ol[1]) + "</li>");
      continue;
    }
    closeList();
    html.push("<p>" + renderInline(line) + "</p>");
  }
  closeList();
  return html.join("");
}

/** 按标题行（# ~ ######）切分报告正文，返回 [{level, heading, body}]。 */
export function parseReportSections(content) {
  const text = String(content == null ? "" : content);
  const sections = [];
  let current = null;
  for (const raw of text.split(/\r?\n/)) {
    const m = raw.match(/^(#{1,6})\s+(.*)$/);
    if (m) {
      if (current) sections.push(current);
      current = { level: m[1].length, heading: m[2].trim(), body: "" };
    } else if (current) {
      current.body += (current.body ? "\n" : "") + raw;
    } else {
      if (!sections.length) sections.push({ level: 0, heading: null, body: "" });
      sections[0].body += (sections[0].body ? "\n" : "") + raw;
    }
  }
  if (current) sections.push(current);
  if (!sections.length) sections.push({ level: 0, heading: null, body: "" });
  return sections;
}

const norm = (s) => String(s == null ? "" : s).replace(/\s+/g, "").toLowerCase();

/** 从标题提取步骤号：「步骤3：xxx」「3. xxx」→ 3，取不到返回 null。 */
function headingStepNo(heading) {
  let m = heading.match(/步骤\s*[:：]?\s*(\d+)/);
  if (m) return Number(m[1]);
  m = heading.match(/^\s*(\d+)\s*[.、．:：]/);
  if (m) return Number(m[1]);
  return null;
}

/**
 * 把图表挂到对应段落：优先步骤号精确匹配，其次步骤名包含匹配。
 * 返回 { sections: [{level, heading, body, chart}], orphans: [] }。
 */
export function attachCharts(sections, charts) {
  const list = (charts || []).filter((c) => (c.rows && c.rows.length) || c.dataStatus === "blocked");
  const used = new Set();
  const out = sections.map((sec) => {
    let chart = null;
    if (sec.heading) {
      const no = headingStepNo(sec.heading);
      const hNorm = norm(sec.heading);
      chart = list.find((c) => !used.has(c) && no != null && c.stepNo === no);
      if (!chart) {
        chart = list.find((c) => {
          if (used.has(c)) return false;
          const n = norm(c.stepName);
          return n && (hNorm.includes(n) || n.includes(hNorm));
        });
      }
      if (chart) used.add(chart);
    }
    return { ...sec, chart };
  });
  return { sections: out, orphans: list.filter((c) => !used.has(c)) };
}
