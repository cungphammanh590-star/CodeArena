package com.codearena.business.knowledge.qdrant;

import com.codearena.business.knowledge.config.KnowledgeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class QdrantClient {

    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper;
    private RestClient rest;

    @PostConstruct
    void init() {
        RestClient.Builder b = RestClient.builder().baseUrl(trimSlash(properties.getQdrant().getUrl()));
        String key = properties.getQdrant().getApiKey();
        if (key != null && !key.isBlank()) {
            b = b.defaultHeader("api-key", key);
        }
        rest = b.build();
        if (properties.isEnabled()) {
            try {
                ensureCollection();
            } catch (Exception e) {
                log.warn("Qdrant ensureCollection failed (will retry on write): {}", e.getMessage());
            }
        }
    }

    public void ensureCollection() {
        String collection = properties.getQdrant().getCollection();
        int dim = properties.getEmbedding().getDim();
        try {
            String json = rest.get().uri("/collections/{name}", collection).retrieve().body(String.class);
            Integer existing = readVectorSize(json);
            if (existing != null && existing == dim) {
                return;
            }
            if (existing != null) {
                log.warn(
                        "Qdrant collection {} dim mismatch (have={}, want={}); recreating",
                        collection,
                        existing,
                        dim);
                rest.delete().uri("/collections/{name}", collection).retrieve().toBodilessEntity();
            }
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
        }
        Map<String, Object> body = Map.of(
                "vectors",
                Map.of(
                        "size", dim,
                        "distance", "Cosine"));
        rest.put()
                .uri("/collections/{name}", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        log.info("Created Qdrant collection {} dim={}", collection, dim);
    }

    private Integer readVectorSize(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode size = objectMapper
                    .readTree(json)
                    .path("result")
                    .path("config")
                    .path("params")
                    .path("vectors")
                    .path("size");
            return size.isNumber() ? size.asInt() : null;
        } catch (Exception e) {
            log.warn("parse qdrant collection config failed: {}", e.getMessage());
            return null;
        }
    }

    public String upsert(
            String pointId,
            float[] vector,
            long userId,
            long kpId,
            long docId,
            String topic,
            int version) {
        ensureCollection();
        String id = pointId == null || pointId.isBlank() ? UUID.randomUUID().toString() : pointId;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", userId);
        payload.put("kp_id", kpId);
        payload.put("doc_id", docId);
        if (topic != null && !topic.isBlank()) {
            payload.put("topic", topic);
        }
        payload.put("version", version);

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id);
        point.put("vector", toList(vector));
        point.put("payload", payload);

        Map<String, Object> body = Map.of("points", List.of(point));
        rest.put()
                .uri("/collections/{name}/points?wait=true", properties.getQdrant().getCollection())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        return id;
    }

    public void deletePoints(List<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> body = Map.of("points", pointIds);
            rest.post()
                    .uri("/collections/{name}/points/delete?wait=true", properties.getQdrant().getCollection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return;
            }
            throw ex;
        }
    }

    public List<SearchHit> search(float[] vector, long userId, String topic, int limit) {
        ensureCollection();
        Map<String, Object> must = new LinkedHashMap<>();
        must.put("key", "user_id");
        must.put("match", Map.of("value", userId));

        List<Map<String, Object>> mustList = new ArrayList<>();
        mustList.add(must);
        if (topic != null && !topic.isBlank()) {
            mustList.add(Map.of("key", "topic", "match", Map.of("value", topic)));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", toList(vector));
        body.put("limit", Math.max(1, Math.min(limit, 20)));
        body.put("with_payload", true);
        body.put("filter", Map.of("must", mustList));

        String json = rest.post()
                .uri("/collections/{name}/points/search", properties.getQdrant().getCollection())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseHits(json);
    }

    private List<SearchHit> parseHits(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("result");
            List<SearchHit> hits = new ArrayList<>();
            if (!result.isArray()) {
                return hits;
            }
            for (JsonNode n : result) {
                JsonNode payload = n.path("payload");
                hits.add(new SearchHit(
                        n.path("id").asText(),
                        n.path("score").asDouble(0),
                        payload.path("kp_id").asLong(0),
                        payload.path("doc_id").asLong(0),
                        payload.path("topic").asText(null)));
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("parse qdrant search failed: " + e.getMessage(), e);
        }
    }

    private static List<Float> toList(float[] v) {
        List<Float> list = new ArrayList<>(v.length);
        for (float x : v) {
            list.add(x);
        }
        return list;
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "http://127.0.0.1:6333";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record SearchHit(String pointId, double score, long kpId, long docId, String topic) {}
}
