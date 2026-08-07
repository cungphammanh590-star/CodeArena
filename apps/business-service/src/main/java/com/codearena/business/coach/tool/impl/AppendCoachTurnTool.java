package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.memory.domain.CoachTurnEntity;
import com.codearena.business.coach.memory.service.CoachSessionService;
import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 内部工具：stream 落轮次，不进入 LangGraph TOOL_SPECS。 */
@Component
@RequiredArgsConstructor
public class AppendCoachTurnTool implements CoachTool {
    private final CoachSessionService sessionService;

    @Override
    public String name() {
        return "append_coach_turn";
    }

    @Override
    public Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public String description() {
        return "内部：追加一条会话轮次（user/assistant）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.sessionId() == null || context.sessionId().isBlank()) {
            return CoachToolResult.failure("session_id required");
        }
        String role = context.paramString("role");
        String content = context.paramString("content");
        if (content == null) {
            content = "";
        }
        String intent = context.paramString("intent");
        String phase = context.paramString("phase");
        CoachTurnEntity turn = sessionService.appendTurn(
                context.sessionId(), context.userId(), role, content, intent, phase);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("turn_id", turn.getId());
        data.put("session_id", turn.getSessionId());
        data.put("role", turn.getRole());
        return CoachToolResult.success(data);
    }
}
