import { describe, expect, it } from "vitest";
import { toUserMessage } from "./userMessage";

describe("toUserMessage", () => {
  it.each([
    [401, "DeepSeek API Key 无效，请检查后重新填写"],
    [402, "DeepSeek 账户余额不足，请充值后重试"],
    [429, "DeepSeek 请求过于频繁，请稍后重试"],
    [503, "DeepSeek 服务暂时不可用，请稍后重试"],
  ])("explains an upstream DeepSeek %s probe failure", (upstream, expected) => {
    const err = {
      response: {
        status: 502,
        data: { detail: `LLM probe failed: upstream ${upstream}: provider response` },
      },
    };

    expect(toUserMessage(err)).toBe(expected);
  });

  it("keeps an unknown server failure generic", () => {
    expect(toUserMessage({ response: { status: 500, data: {} } })).toBe(
      "服务暂时不可用，请稍后再试",
    );
  });
});
