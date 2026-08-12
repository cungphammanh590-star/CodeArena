/**
 * 把接口/异常原文转成面向终端用户的中文提示。
 * 开发者细节（超时原文、HTTP 状态码、服务名、堆栈）不得直接展示。
 */

const DEFAULT_FALLBACK = "出了点问题，请稍后再试";

function extractRaw(err: unknown): string {
  if (err == null) return "";
  if (typeof err === "string") return stripErrorPrefix(err.trim());
  if (err instanceof Error) return stripErrorPrefix((err.message || "").trim());
  if (typeof err === "object") {
    const o = err as {
      message?: unknown;
      response?: { data?: { message?: unknown }; status?: number };
      status?: number;
      code?: string;
    };
    const apiMsg = o.response?.data?.message;
    if (typeof apiMsg === "string" && apiMsg.trim()) {
      return stripErrorPrefix(apiMsg.trim());
    }
    if (typeof o.message === "string" && o.message.trim()) {
      return stripErrorPrefix(o.message.trim());
    }
    if (typeof o.code === "string" && o.code.trim()) return o.code.trim();
  }
  return stripErrorPrefix(String(err).trim());
}

function stripErrorPrefix(raw: string): string {
  return raw
    .replace(/^(AxiosError|TypeError|Error|RuntimeError):\s*/i, "")
    .trim();
}

function httpStatus(err: unknown): number | null {
  if (!err || typeof err !== "object") return null;
  const o = err as {
    response?: { status?: number };
    status?: number;
  };
  const s = o.response?.status ?? o.status;
  return typeof s === "number" ? s : null;
}

/** 已知技术原文 → 用户话术 */
function mapTechnical(raw: string): string | null {
  const t = raw.toLowerCase();

  if (
    /timed?\s*out|timeout|etimedout|超时|响应超时/.test(t) ||
    /deadline exceeded/.test(t)
  ) {
    return "请求超时了，请稍后再试";
  }
  if (
    /network error|failed to fetch|econnrefused|enotfound|econnreset|connecterror|connect(ion)? (refused|error)|连不上|无法连接|err_connection/.test(
      t,
    )
  ) {
    return "暂时连不上服务，请检查网络后重试";
  }
  if (/abort(ed)?|cancel(led)?/.test(t) && !/请/.test(raw)) {
    return "请求已取消";
  }
  if (/401|unauthorized|jwt|token.*(invalid|expired|missing)|登录.*(失效|过期)/.test(t)) {
    return "登录已失效，请重新登录";
  }
  if (/403|forbidden|当前账号无法使用/.test(t)) {
    return "没有权限执行此操作";
  }
  if (/404|not found|找不到/.test(t) && /http|status|api|endpoint|submission|session/.test(t)) {
    return "找不到相关内容";
  }
  if (/429|too many|rate.?limit|频繁/.test(t)) {
    return "操作太频繁，请稍后再试";
  }
  if (/50[0-9]|bad gateway|service unavailable|gateway/.test(t)) {
    return "服务暂时不可用，请稍后再试";
  }
  if (/status code\s*\d+|request failed/i.test(raw)) {
    return "服务暂时出了点问题，请稍后再试";
  }

  // 基础设施 / 内部实现泄漏
  if (
    /business-service|llm-service|gateway|8090|8091|8080|redis|postgres|checkpoint|json\.set|httpx|traceback|stack trace|internal.?token|x-user-public-id|nacos|flyway/i.test(
      raw,
    )
  ) {
    if (/llm|模型|api.?key|陪练|教练|nex/i.test(raw)) {
      return "暂时读不到你的模型配置，请稍后再试；若刚改过设置，可到维护台确认后重试";
    }
    return "服务暂时不可用，请稍后再试";
  }

  if (
    /provider\s*=|submit_user_reply|message 或 action|hint failed|^failed$|l2 persist|checkpointer|unsupported operand|typeerror|runtimeerror|valueerror/i.test(
      raw,
    )
  ) {
    return DEFAULT_FALLBACK;
  }

  if (/无法读取.*llm|读取.*llm.?配置|llm 配置/i.test(raw)) {
    return "暂时读不到你的模型配置，请稍后再试";
  }

  return null;
}

/** 像已经写给用户的中文短句（无堆栈、无明显英文技术词） */
function looksUserFacing(raw: string): boolean {
  if (!raw || raw.length > 160) return false;
  if (/[{}\[\]\\|]| at \w+\.|Exception|Error:|Traceback|localhost:\d+/i.test(raw)) {
    return false;
  }
  // 至少含有常用汉字，且不以纯英文技术短语为主
  const han = (raw.match(/[\u4e00-\u9fff]/g) || []).length;
  if (han < 2) return false;
  if (/^[A-Za-z0-9_./:\s-]+$/.test(raw)) return false;
  return true;
}

/**
 * @param err 原始异常、接口 message、或任意 unknown
 * @param fallback 无法识别时的兜底文案
 */
export function toUserMessage(
  err: unknown,
  fallback: string = DEFAULT_FALLBACK,
): string {
  const status = httpStatus(err);
  if (status === 401) return "登录已失效，请重新登录";
  if (status === 403) return "没有权限执行此操作";
  if (status === 404) return "找不到相关内容";
  if (status === 429) return "操作太频繁，请稍后再试";
  if (status != null && status >= 500) return "服务暂时不可用，请稍后再试";

  const raw = extractRaw(err);
  if (!raw) return fallback;

  const mapped = mapTechnical(raw);
  if (mapped) return mapped;

  if (looksUserFacing(raw)) return raw;

  return fallback;
}
