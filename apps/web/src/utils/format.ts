export function chinaTodayStr(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

export function shiftDate(iso: string, delta: number): string {
  const [y, m, d] = iso.split("-").map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  dt.setUTCDate(dt.getUTCDate() + delta);
  return dt.toISOString().slice(0, 10);
}

export interface PageState<T> {
  page: number;
  totalPages: number;
  total: number;
  start: number;
  end: number;
  items: T[];
}

export function paginate<T>(
  list: T[],
  page: number,
  pageSize: number,
): PageState<T> {
  const total = list.length;
  const totalPages = Math.max(1, Math.ceil(total / pageSize) || 1);
  const safePage = Math.min(Math.max(1, page), totalPages);
  const start = (safePage - 1) * pageSize;
  return {
    page: safePage,
    totalPages,
    total,
    start: total ? start + 1 : 0,
    end: Math.min(start + pageSize, total),
    items: list.slice(start, start + pageSize),
  };
}

export function formatStatusCounts(
  counts: Record<string, number> | null | undefined,
  excludeAccepted = false,
): string {
  const entries = Object.entries(counts || {});
  const filtered = excludeAccepted
    ? entries.filter(([s]) => s !== "Accepted")
    : entries;
  return (
    filtered
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .map(([status, count]) => `${status} ×${count}`)
      .join("，") || "—"
  );
}

export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null) return "尚未 AC";
  if (seconds < 60) return `${seconds} 秒`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`;
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return `${h} 小时${m ? ` ${m} 分` : ""}`;
}

export function leetcodeUrl(slug: string | null | undefined): string | null {
  if (!slug || /^problem-\d+$/i.test(slug)) return null;
  return `https://leetcode.cn/problems/${encodeURIComponent(slug)}/`;
}

export function diffClass(diff: string): string {
  if (diff === "Easy") return "diff-easy";
  if (diff === "Medium") return "diff-medium";
  if (diff === "Hard") return "diff-hard";
  return "";
}

export function statusChangeLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    first_ac: "首次 AC",
    improved: "进步",
    declined: "退步",
    stuck: "卡住",
  };
  return (code && map[code]) || code || "—";
}

export function statusChangeClass(code: string | null | undefined): string {
  if (!code) return "pill";
  return `pill pill-${code}`;
}
