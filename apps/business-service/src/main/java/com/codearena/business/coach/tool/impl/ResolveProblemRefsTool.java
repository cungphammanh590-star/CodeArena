package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.plan.service.ProblemResolveService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 解析用户粘贴的力扣题号/标题，并标注已刷/未刷。 */
@Component
@RequiredArgsConstructor
public class ResolveProblemRefsTool implements CoachTool {

    private final ProblemResolveService resolveService;

    @Override
    public String name() {
        return "resolve_problem_refs";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "解析用户给出的力扣题号/标题列表，返回 matched/ambiguous/unmatched，"
                + "并标注 Accepted/掌握与 remaining_ids。"
                + "纯题号优先匹配；可回退 goal_problem_banks 并自动入库。"
                + "策略：用 matched/remaining_ids 继续 preview/generate，不要因 unmatched 卡住；"
                + "把 unmatched/ambiguous 在回复里告知用户即可（题单可后续改）。"
                + "仅当 matched 为空时才需要向用户澄清。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        List<String> queries = toStringList(context.param("queries"));
        String raw = context.paramString("raw_text");
        if ((queries == null || queries.isEmpty()) && (raw == null || raw.isBlank())) {
            return CoachToolResult.failure("需要 queries 或 raw_text");
        }
        Map<String, Object> data = resolveService.resolve(context.userId(), queries, raw);
        return CoachToolResult.success(data);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }
}
