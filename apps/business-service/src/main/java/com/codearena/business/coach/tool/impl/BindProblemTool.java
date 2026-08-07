package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.memory.service.CoachSessionService;
import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BindProblemTool implements CoachTool {
    private final ProblemRepository problemRepository;
    private final CoachSessionService sessionService;

    @Override
    public String name() {
        return "bind_problem";
    }

    @Override
    public Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public String description() {
        return "将本会话绑定到一道题。用户给出题号或标题时调用。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        Integer pid = context.paramInt("problem_id");
        if (pid == null) {
            pid = context.problemId();
        }
        String query = context.paramString("query");
        Map<String, Object> data = new LinkedHashMap<>();
        if (pid != null && pid > 0) {
            if (context.sessionId() != null && !context.sessionId().isBlank()) {
                sessionService.bindProblem(context.sessionId(), context.userId(), pid);
            }
            data.put("ok", true);
            data.put("problem_id", pid);
            data.put("bound", true);
            data.put("session_id", context.sessionId());
            data.put("note", "已绑定并写入 coach_sessions");
            problemRepository
                    .findByProblemId(pid)
                    .ifPresent(p -> {
                        data.put("title", p.getTitle());
                        data.put("slug", p.getSlug());
                    });
            return CoachToolResult.success(data);
        }
        if (query != null && !query.isBlank()) {
            List<Map<String, Object>> candidates = problemRepository.findAll().stream()
                    .filter(p -> p.getTitle() != null
                            && p.getTitle().toLowerCase().contains(query.toLowerCase()))
                    .limit(5)
                    .map(this::brief)
                    .collect(Collectors.toList());
            data.put("ok", false);
            data.put("candidates", candidates);
            data.put("note", candidates.isEmpty() ? "无匹配题目" : "请让用户确认题号后再 bind");
            return CoachToolResult.success(data);
        }
        return CoachToolResult.failure("需要 problem_id 或 query");
    }

    private Map<String, Object> brief(ProblemEntity p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("problem_id", p.getProblemId());
        m.put("title", p.getTitle());
        m.put("slug", p.getSlug());
        return m;
    }
}
