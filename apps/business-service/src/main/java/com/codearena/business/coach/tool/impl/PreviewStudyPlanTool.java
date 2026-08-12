package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.learning.plan.service.PlanGenerationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 只算不写：预览题单容量与已刷过滤，确认后再 generate_study_plan。 */
@Component
@RequiredArgsConstructor
public class PreviewStudyPlanTool implements CoachTool {

    private final PlanGenerationService planGenerationService;

    @Override
    public String name() {
        return "preview_study_plan";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "预览刷题计划（不落库）：解析容量（天数/每日题量）、已刷过滤。"
                + "用户贴题单时传 problem_ids（来自 resolve_problem_refs）。"
                + "解析不完全时仍可用已匹配的 remaining_ids 预览，把未匹配项告诉用户即可。"
                + "need_user_choice=true 时用 ask_user 让用户放宽天数或强度，确认后再 generate_study_plan。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.userId() == null) {
            return CoachToolResult.failure("user required");
        }
        Map<String, Object> result = planGenerationService.preview(toCommand(context));
        boolean ok = Boolean.TRUE.equals(result.get("ok"))
                || Boolean.TRUE.equals(result.get("need_user_choice"));
        if (!ok && result.get("note") != null) {
            return new CoachToolResult(false, result, String.valueOf(result.get("note")));
        }
        return CoachToolResult.success(result);
    }

    static PlanGenerationService.GenerateCommand toCommand(CoachToolContext context) {
        Boolean schedule = null;
        Object sch = context.param("schedule");
        if (sch instanceof Boolean b) {
            schedule = b;
        } else if (sch != null) {
            schedule = Boolean.parseBoolean(String.valueOf(sch));
        }
        Integer days = context.paramInt("days");
        if (schedule == null) {
            schedule = days != null && days > 0;
        }
        Boolean skipPassed = null;
        Object sp = context.param("skip_passed");
        if (sp instanceof Boolean b) {
            skipPassed = b;
        } else if (sp != null) {
            skipPassed = Boolean.parseBoolean(String.valueOf(sp));
        }
        Boolean force = null;
        Object f = context.param("force");
        if (f instanceof Boolean b) {
            force = b;
        } else if (f != null) {
            force = Boolean.parseBoolean(String.valueOf(f));
        }
        String goalType = context.paramString("goal_type");
        if ((goalType == null || goalType.isBlank()) && !toIntList(context.param("problem_ids")).isEmpty()) {
            goalType = "custom";
        }
        String goalRef = context.paramString("goal_ref");
        if ((goalRef == null || goalRef.isBlank()) && "custom".equalsIgnoreCase(goalType)) {
            goalRef = "custom";
        }
        return new PlanGenerationService.GenerateCommand(
                context.userId(),
                goalType,
                goalRef,
                context.paramString("title"),
                days,
                context.paramInt("daily_goal"),
                schedule,
                context.paramString("difficulty"),
                context.paramInt("limit"),
                toIntList(context.param("problem_ids")),
                skipPassed,
                force);
    }

    static List<Integer> toIntList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<Integer> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                try {
                    out.add(Integer.valueOf(String.valueOf(o).trim()));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            return out;
        }
        try {
            return List.of(Integer.valueOf(String.valueOf(raw).trim()));
        } catch (NumberFormatException ex) {
            return List.of();
        }
    }
}
