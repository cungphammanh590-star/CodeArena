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

/** 内部工具：同步 session phase / problem_id / summary / topic / 关闭状态。 */
@Component
@RequiredArgsConstructor
public class SyncSessionStateTool implements CoachTool {
    private final CoachSessionService sessionService;

    @Override
    public String name() {
        return "sync_session_state";
    }

    @Override
    public Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public String description() {
        return "内部：同步会话 phase/problem_id/summary/topic/status。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.sessionId() == null || context.sessionId().isBlank()) {
            return CoachToolResult.failure("session_id required");
        }
        String phase = context.paramString("phase");
        Integer problemId = context.paramInt("problem_id");
        if (problemId == null) {
            problemId = context.problemId();
        }
        String summary = context.paramString("summary");
        String topic = context.paramString("topic");
        String closeScope = context.paramString("close_scope");
        boolean close = "session".equalsIgnoreCase(closeScope)
                || Boolean.TRUE.equals(context.param("done"))
                || "true".equalsIgnoreCase(String.valueOf(context.param("close")));
        CoachSessionEntity session = sessionService.syncState(
                context.sessionId(),
                context.userId(),
                phase,
                problemId,
                close,
                summary,
                topic);
        Map<String, Object> data = new LinkedHashMap<>(sessionService.toView(session));
        return CoachToolResult.success(data);
    }
}
