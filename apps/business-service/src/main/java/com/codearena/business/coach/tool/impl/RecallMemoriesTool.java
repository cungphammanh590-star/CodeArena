package com.codearena.business.coach.tool.impl;

import com.codearena.business.coach.memory.service.CoachMemoryService;
import com.codearena.business.coach.tool.CoachTool;
import com.codearena.business.coach.tool.CoachToolContext;
import com.codearena.business.coach.tool.CoachToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecallMemoriesTool implements CoachTool {
    private final CoachMemoryService memoryService;

    @Override
    public String name() {
        return "recall_memories";
    }

    @Override
    public Kind kind() {
        return Kind.READ;
    }

    @Override
    public String description() {
        return "读取当前用户的活跃长期教练记忆（偏好/薄弱点/目标/笔记）。";
    }

    @Override
    public CoachToolResult execute(CoachToolContext context) {
        String kind = context.paramString("kind");
        int limit = 8;
        Integer lim = context.paramInt("limit");
        if (lim != null) {
            limit = lim;
        }
        List<Map<String, Object>> items = memoryService.recall(context.userId(), kind, limit).stream()
                .map(memoryService::toView)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("count", items.size());
        return CoachToolResult.success(data);
    }
}
