package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.memory.domain.CoachSessionEntity;
import com.codearena.business.coach.memory.service.CoachSessionService;
import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetSessionBindingTool implements CoachTool {
    private final CoachSessionService sessionService;

    @Override
    public String name() {
        return "get_session_binding";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "查看当前会话是否已绑定题目。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", context.sessionId());
        data.put("user_public_id", context.userPublicId());
        Integer pid = context.problemId();
        String phase = null;
        if (context.sessionId() != null && !context.sessionId().isBlank()) {
            try {
                CoachSessionEntity session =
                        sessionService.requireOwned(context.sessionId(), context.userId());
                pid = session.getProblemId() != null ? session.getProblemId() : pid;
                phase = session.getPhase();
                data.put("status", session.getStatus());
            } catch (Exception ignored) {
                // session may not exist yet for ad-hoc streams
            }
        }
        data.put("problem_id", pid);
        data.put("bound", pid != null && pid > 0);
        data.put("phase", phase);
        return CoachToolResult.success(data);
    }
}
