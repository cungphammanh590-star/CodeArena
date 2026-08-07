package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.memory.domain.CoachSessionEntity;
import com.codearena.business.coach.memory.domain.CoachTurnEntity;
import com.codearena.business.coach.memory.service.CoachSessionService;
import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** hydrate：会话绑定 + topic + 近 N 轮 turns。 */
@Component
@RequiredArgsConstructor
public class GetSessionContextTool implements CoachTool {
    private final CoachSessionService sessionService;

    @Override
    public String name() {
        return "get_session_context";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "读取会话上下文：绑定题、topic、session_kind、summary 与近几轮对话。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        if (context.sessionId() == null || context.sessionId().isBlank()) {
            return CoachToolResult.failure("session_id required");
        }
        int limit = 16;
        Integer lim = context.paramInt("limit");
        if (lim != null) {
            limit = Math.max(1, Math.min(50, lim));
        }
        CoachSessionEntity session = sessionService.ensure(context.sessionId(), context.userId());
        List<CoachTurnEntity> turns =
                sessionService.listTurns(session.getSessionId(), context.userId());
        if (turns.size() > limit) {
            turns = turns.subList(turns.size() - limit, turns.size());
        }
        List<Map<String, Object>> turnViews = new ArrayList<>();
        for (CoachTurnEntity t : turns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", t.getRole());
            m.put("content", t.getContent());
            m.put("intent", t.getIntent());
            m.put("phase", t.getPhase());
            turnViews.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>(sessionService.toView(session));
        data.put("turns", turnViews);
        data.put("ok", true);
        return CoachToolResult.success(data);
    }
}
