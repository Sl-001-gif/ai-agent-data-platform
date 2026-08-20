/**
 * 共享图表构建器：为 AI 生成的数据结果做「业务自适应」预处理。
 * 解决：①整体/部分量级悬殊（如全市 vs 区县）自动启用双 Y 轴；②X 轴期间标签标准化与时间排序；
 * ③图例默认折叠次要序列、右轴归属标注；④图表标题、Y 轴单位与量纲语义；⑤tooltip 带单位格式化。
 */
export const CHART_TYPES = ["line", "bar", "pie"];

/** 量级悬殊阈值：最大序列 / 其余序列中位数 >= 该值则拆分到右轴。 */
const SPLIT_RATIO = 5;
/** 汇总类关键词：全市/合计等命中时放宽阈值（业务上整体与部分不应同轴）。 */
const TOTAL_KEYWORDS = /(全市|总计|合计|全部|市本级|整体)/;
/** 默认保持可见的序列总数上限（其余图例折叠，点击图例可展开）。 */
const DEFAULT_SHOWN = 8;
const MANY_ROWS = 8;

export function pickChartColumns(columns, rows) {
  if (!columns || !columns.length) return {};
  const nameKey = columns[0];
  let valueKey = null;
  let unitKey = null;
  for (let i = 1; i < columns.length; i++) {
    const col = columns[i];
    if (col === "unit" || col === "period" || col === "期间") {
      if (!unitKey) unitKey = col;
      continue;
    }
    const allNumeric = rows.length > 0 && rows.every((r) => r[col] == null || r[col] === "" || !Number.isNaN(Number(r[col])));
    if (col === "value") valueKey = col;
    if (allNumeric && !valueKey) valueKey = col;
  }
  if (!valueKey) valueKey = columns[columns.length - 1];
  let unit = "";
  if (unitKey) {
    for (const r of rows) {
      if (r[unitKey] != null && r[unitKey] !== "") {
        unit = String(r[unitKey]);
        break;
      }
    }
  }
  return { nameKey, valueKey, unit };
}

function seriesValues(rows, nameKey, valueKey, axisItems, filter) {
  const groupRows = filter ? rows.filter(filter) : rows;
  return axisItems.map((item) => {
    const hit = groupRows.find((r) => String(r[nameKey]) === item.raw);
    return hit == null || hit[valueKey] == null ? null : Number(hit[valueKey]);
  });
}

function seriesMax(data) {
  let max = -Infinity;
  for (const v of data) {
    if (v != null && v > max) max = v;
  }
  return max === -Infinity ? 0 : max;
}

/* ---------- 期间/时间标签标准化与排序 ---------- */
const PERIOD_FULL_RE = /^(\d{4})\s*年\s*(\d{1,2})\s*[-－]\s*(\d{1,2})\s*月(累计)?$/;
const PERIOD_CROSS_RE = /^(\d{4})\s*年\s*(\d{1,2})\s*月\s*[-－]\s*(\d{4})\s*年\s*(\d{1,2})\s*月$/;
const YEAR_ONLY_RE = /^(\d{4})\s*年$/;
const CUM_ONLY_RE = /^(\d{1,2})\s*[-－]\s*(\d{1,2})\s*月(累计)?$/;
const MONTH_END_RE = /^(\d{1,2})\s*月末$/;

/**
 * 把原始期间字符串标准化为展示标签 + 时间排序键。
 * 支持：2018年1-03月 / 2024年1-9月累计 / 2018年 / 1-6月累计 / 3月末 等；
 * 无法解析的标签原样保留（不裁切、不拼接），保证不产生 9月-3月 这类乱标签。
 */
export function normalizePeriod(raw) {
  const s = String(raw == null ? "" : raw).trim();
  let m;
  if ((m = s.match(PERIOD_FULL_RE))) {
    const y = Number(m[1]);
    const a = Number(m[2]);
    const b = Number(m[3]);
    const end = b < a ? b + 12 : b; // 跨年兜底
    return { label: m[1] + "年" + a + "月-" + b + "月" + (m[4] || ""), key: y * 100 + end, tie: 1 };
  }
  if ((m = s.match(PERIOD_CROSS_RE))) {
    return {
      label: m[1] + "年" + Number(m[2]) + "月-" + m[3] + "年" + Number(m[4]) + "月",
      key: Number(m[3]) * 100 + Number(m[4]),
      tie: 1,
    };
  }
  if ((m = s.match(YEAR_ONLY_RE))) {
    return { label: s, key: Number(m[1]) * 100 + 12, tie: 0 };
  }
  if ((m = s.match(CUM_ONLY_RE))) {
    const a = Number(m[1]);
    const b = Number(m[2]);
    return { label: a + "月-" + b + "月" + (m[3] || ""), key: b < a ? b + 12 : b, tie: 2 };
  }
  if ((m = s.match(MONTH_END_RE))) {
    return { label: Number(m[1]) + "月末", key: Number(m[1]), tie: 2 };
  }
  return { label: s, key: Infinity, tie: 3 };
}

/** 期间累计月数（粒度）：2024年1-9月 → 9、2024年1-12月 → 12；无法判定返回 null。用于趋势图粒度守卫。 */
function periodGranularity(raw) {
  const s = String(raw == null ? "" : raw).trim();
  let m;
  if ((m = s.match(PERIOD_FULL_RE)) || (m = s.match(CUM_ONLY_RE))) {
    let a = Number(m[2]);
    let b = Number(m[3]);
    if (b < a) b += 12;
    return b - a + 1;
  }
  if ((m = s.match(YEAR_ONLY_RE))) return 12;
  return null;
}

/** X 轴期间标签：标准化 + 按时间排序，返回 [{raw, label}]（data 按 raw 取值、xAxis 按 label 展示）。 */
function sortAxisItems(rawLabels) {
  return rawLabels
    .map((raw) => ({ raw, info: normalizePeriod(raw) }))
    .sort((a, b) => a.info.key - b.info.key || a.info.tie - b.info.tie || a.raw.localeCompare(b.raw, "zh"))
    .map((x) => ({ raw: x.raw, label: x.info.label }));
}

/** 图例默认选中策略：汇总序列 + 增速序列（非分组或汇总增速）默认显示，其余按数值从大到小补足。 */
function legendSelected(series, groupKey) {
  const selected = {};
  const shown = new Set();
  series.forEach((s, i) => {
    if (s.__growth) {
      if (!groupKey || TOTAL_KEYWORDS.test(s.name)) shown.add(i);
    } else if (TOTAL_KEYWORDS.test(s.name)) {
      shown.add(i);
    }
  });
  const ranked = series.map((s, i) => ({ i, m: seriesMax(s.data) })).sort((a, b) => b.m - a.m);
  for (const { i } of ranked) {
    if (shown.size >= DEFAULT_SHOWN) break;
    shown.add(i);
  }
  series.forEach((s, i) => {
    selected[s.name] = shown.has(i);
  });
  return selected;
}

/**
 * @param {string} chartType line/bar/pie
 * @param {string[]} columns 查询结果列名
 * @param {object[]} rows 查询结果行
 * @param {{title?: string}} options 可选：图表标题
 */
export function buildChartOption(chartType, columns, rows, options = {}) {
  if (!columns || !columns.length || !rows || !rows.length) return null;
  const title = options.title || "";
  const pick = pickChartColumns(columns, rows);
  let nameKey = pick.nameKey;
  const valueKey = pick.valueKey;
  const unit = pick.unit;
  const many = rows.length > MANY_ROWS;
  /** 期间列：饼图用于取最新一期、柱状图用于快照维度交换。 */
  const periodCol = columns.includes("period") ? "period" : columns.includes("期间") ? "期间" : null;

  const titleOption = title
    ? { text: title, left: "center", top: 0, textStyle: { fontSize: 14, fontWeight: 600 } }
    : undefined;

  if (chartType === "pie") {
    // 容器守卫：饼图只允许渲染单一时间断面的分类数据；首列是期间时改按产业/区县分类
    let pieRows = rows;
    let pieNameKey = nameKey;
    if (periodCol) {
      const periods = [...new Set(rows.map((r) => String(r[periodCol])))];
      if (periods.length > 1) {
        const latest = sortAxisItems(periods).slice(-1)[0];
        pieRows = rows.filter((r) => String(r[periodCol]) === latest.raw);
      }
      if (nameKey === periodCol) {
        const alt = columns.find((c) => c === "industry" || c === "产业" || c === "region" || c === "区县");
        if (alt) pieNameKey = alt;
        else return null; // 维度不支持饼图，阻断渲染（避免把时间切成扇区）
      }
    }
    if (!pieRows.length) return null;
    return {
      title: titleOption,
      tooltip: { trigger: "item", formatter: unit ? (p) => p.name + "：" + p.value + " " + unit : undefined },
      legend: { bottom: 0, type: "scroll" },
      series: [
        {
          name: valueKey,
          type: "pie",
          radius: "62%",
          data: pieRows.map((row) => ({ name: String(row[pieNameKey]), value: Number(row[valueKey]) })),
        },
      ],
    };
  }

  const hasRegion = columns.includes("region") || columns.includes("区县");
  const hasIndustry =
    columns.includes("industry") || columns.includes("产业") || columns.includes("indicator") || columns.includes("指标");
  const regionKey = columns.includes("region") ? "region" : "区县";
  const industryKey = columns.includes("industry")
    ? "industry"
    : columns.includes("产业")
      ? "产业"
      : columns.includes("indicator")
        ? "indicator"
        : "指标";
  const dimKey = hasRegion ? regionKey : (hasIndustry ? industryKey : null);
  // 维度守卫：X 轴是期间列但数据为「单期多区县」快照 → X 轴切换为区县（各区县对比柱状图）
  if (hasRegion && periodCol && nameKey === periodCol) {
    const periodSet = new Set(rows.map((r) => String(r[periodCol])));
    const regionSet = new Set(rows.map((r) => String(r[regionKey])));
    if (periodSet.size <= 1 && regionSet.size > 1) {
      nameKey = regionKey;
    }
  }
  // —— 期间粒度守卫 + 样本量守卫（仅趋势：X 轴为时间列） ——
  let plotRows = rows;
  let displayTitle = title;
  if (periodCol && nameKey === periodCol) {
    const granOf = (r) => periodGranularity(String(r[periodCol] ?? ""));
    const distinct = [...new Set(plotRows.map(granOf).filter((g) => g != null))];
    if (distinct.length > 1) {
      // 粒度混杂（如 1-12月 与 1-2月 混画）→ 保留出现最多的粒度（完整年份优先），避免量纲不同的点连线误导
      const countByGran = new Map();
      for (const g of plotRows.map(granOf)) {
        if (g != null) countByGran.set(g, (countByGran.get(g) || 0) + 1);
      }
      let keepGran = null;
      let best = 0;
      for (const [g, c] of countByGran) {
        if (c > best) {
          best = c;
          keepGran = g;
        }
      }
      const kept = plotRows.filter((r) => granOf(r) === keepGran);
      if (kept.length >= 2) {
        plotRows = kept;
        const latestKept = sortAxisItems([...new Set(kept.map((r) => String(r[periodCol] ?? "")))]).slice(-1)[0];
        const m = String(latestKept.raw).match(/(\d{4})\s*年\s*\d{1,2}\s*[-－]\s*(\d{1,2})\s*月/);
        displayTitle += "（口径：" + (m ? m[1] + "年1-" + m[2] + "月同期" : "同期口径") + "）";
      }
    }
    const pointCount = new Set(plotRows.map((r) => String(r[periodCol] ?? ""))).size;
    if (pointCount === 0) return null;
    if (pointCount < 3) displayTitle += "（样本不足：仅 " + pointCount + " 期）";
  }
  const groupKey =
    dimKey && nameKey !== dimKey && new Set(plotRows.map((r) => r[dimKey] ?? "未知")).size > 1 ? dimKey : null;
  const axisItems = sortAxisItems([...new Set(plotRows.map((row) => String(row[nameKey] ?? "")))]);
  const xAxisData = axisItems.map((x) => x.label);
  const series = [];

  // 增速列存在且有值 → 追加增速系列（独立 % 右轴，与绝对值量纲分离）
  const growthCol =
    columns.includes("growth_rate") &&
    columns.includes("value") &&
    plotRows.some((r) => r.growth_rate != null && r.growth_rate !== "")
      ? "growth_rate"
      : null;

  const pushValueSeries = (name, filter) => {
    series.push({
      name: String(name),
      type: chartType,
      data: seriesValues(plotRows, nameKey, valueKey, axisItems, filter),
      smooth: chartType === "line",
    });
  };

  if (groupKey) {
    for (const g of [...new Set(rows.map((r) => r[dimKey] ?? "未知"))]) {
      pushValueSeries(g, (r) => (r[dimKey] ?? "未知") === g);
    }
  } else {
    pushValueSeries(valueKey);
  }

  if (growthCol) {
    const groups = groupKey ? [...new Set(rows.map((r) => r[dimKey] ?? "未知"))] : [null];
    for (const g of groups) {
      const filter = g == null ? undefined : (r) => (r[dimKey] ?? "未知") === g;
      const data = seriesValues(plotRows, nameKey, growthCol, axisItems, filter);
      if (data.some((v) => v != null)) {
        series.push({
          name: g == null ? "增速（%）" : "增速（%）·" + g,
          type: "line",
          __growth: true,
          smooth: true,
          lineStyle: { type: "dashed" },
          data,
        });
      }
    }
  }

  // 自动双轴：最大序列与其余序列中位数量级悬殊时拆分到右轴（如 全市 vs 区县，排除邵东市类次大值干扰）
  let splitIndex = -1;
  if (groupKey && series.length >= 2) {
    const maxima = series.map((s) => seriesMax(s.data));
    const nonZero = maxima.map((m, i) => ({ m, i })).filter((x) => x.m > 0);
    if (nonZero.length >= 2) {
      nonZero.sort((a, b) => b.m - a.m);
      const top = nonZero[0];
      const rest = nonZero.slice(1).map((x) => x.m).sort((a, b) => b - a);
      const median = rest[Math.floor(rest.length / 2)];
      const isTotal = TOTAL_KEYWORDS.test(series[top.i].name);
      if (median > 0 && (top.m / median >= SPLIT_RATIO || (isTotal && top.m / median >= 3))) {
        splitIndex = top.i;
        series[splitIndex] = Object.assign({}, series[splitIndex], { lineStyle: { width: 2.5 } });
      }
    }
  }
  const hasSplit = splitIndex >= 0;
  const hasGrowth = series.some((s) => s.__growth);

  // Y 轴组装：左轴（其余/区县）→ 右轴（汇总最大值）→ 右轴（增速 %）
  const regionAxis = groupKey === "region" || groupKey === "区县";
  const leftName = unit ? (regionAxis ? "单位：" + unit + "（区县）" : "单位：" + unit) : undefined;
  const yAxis = [{ type: "value", name: leftName, position: "left" }];
  if (hasSplit) {
    yAxis.push({
      type: "value",
      position: "right",
      name: (unit ? "单位：" + unit + " · " : "") + series[splitIndex].name,
      splitLine: { show: false },
    });
  }
  if (hasGrowth) {
    yAxis.push({
      type: "value",
      position: "right",
      offset: hasSplit ? 70 : 0,
      name: "增速（%）",
      splitLine: { show: false },
      axisLabel: { formatter: (v) => v + "%" },
    });
  }
  series.forEach((s) => {
    if (s.__growth) s.yAxisIndex = yAxis.length - 1;
    else if (hasSplit && series.indexOf(s) === splitIndex) s.yAxisIndex = 1;
  });

  const legendTop = title ? 26 : 0;
  const gridRight = hasSplit && hasGrowth ? 150 : 80;

  return {
    title: displayTitle
      ? { text: displayTitle, left: "center", top: 0, textStyle: { fontSize: 14, fontWeight: 600 } }
      : undefined,
    tooltip: {
      trigger: "axis",
      formatter: (params) => {
        if (!Array.isArray(params)) params = [params];
        const head = (params[0] && params[0].axisValue != null ? params[0].axisValue : "") + "<br/>";
        return (
          head +
          params
            .map((p) => {
              const isGrowth = String(p.seriesName).indexOf("增速") === 0;
              const u = isGrowth ? "%" : unit || "";
              return p.marker + p.seriesName + "：" + (p.value == null ? "-" : p.value + (u ? " " + u : ""));
            })
            .join("<br/>")
        );
      },
    },
    grid: { left: 80, right: gridRight, top: title ? 56 : 30, bottom: 40 },
    xAxis: {
      type: "category",
      data: xAxisData,
      axisLabel: many ? { rotate: 45, interval: "auto", hideOverlap: true } : {},
    },
    yAxis,
    legend:
      series.length > 1
        ? {
            top: legendTop,
            type: "scroll",
            selected: legendSelected(series, groupKey),
            formatter: (name) => {
              const s = series.find((x) => x.name === name);
              return s && s.yAxisIndex > 0 ? name + "（右轴）" : name;
            },
          }
        : undefined,
    series,
  };
}
