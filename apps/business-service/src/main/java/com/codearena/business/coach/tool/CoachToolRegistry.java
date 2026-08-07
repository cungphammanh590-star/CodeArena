package com.codearena.business.coach.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 策略注册表：按 tool_name 分发。 */
@Component
public class CoachToolRegistry {

    private final Map<String, CoachTool> byName;

    public CoachToolRegistry(List<CoachTool> tools) {
        this.byName = tools.stream()
                .collect(Collectors.toMap(
                        CoachTool::name,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "duplicate coach tool: " + a.name());
                        },
                        LinkedHashMap::new));
    }

    public CoachTool require(String name) {
        CoachTool tool = byName.get(name);
        if (tool == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "unknown tool: " + name);
        }
        return tool;
    }

    public Collection<CoachTool> all() {
        return byName.values();
    }

    public List<Map<String, Object>> catalog() {
        return byName.values().stream()
                .map(t -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", t.name());
                    item.put("kind", t.kind().name());
                    item.put("description", t.description());
                    item.put("executor", "business-service");
                    return item;
                })
                .toList();
    }
}
