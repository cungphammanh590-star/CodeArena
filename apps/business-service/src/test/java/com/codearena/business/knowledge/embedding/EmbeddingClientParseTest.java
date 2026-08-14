package com.codearena.business.knowledge.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmbeddingClientParseTest {

    @Test
    void detectsMultimodalUrlOrModel() {
        assertTrue(EmbeddingClient.isMultimodal(
                "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal", "x"));
        assertTrue(EmbeddingClient.isMultimodal(
                "https://example/v1/embeddings", "doubao-embedding-vision-251215"));
        assertFalse(EmbeddingClient.isMultimodal(
                "https://ark.cn-beijing.volces.com/api/v3/embeddings", "doubao-embedding-text-240715"));
    }

    @Test
    void buildsMultimodalBody() {
        Map<String, Object> body = EmbeddingClient.buildHttpBody("m", "hello", true);
        assertEquals("m", body.get("model"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) body.get("input");
        assertEquals("text", input.get(0).get("type"));
        assertEquals("hello", input.get(0).get("text"));
    }

    @Test
    void parsesArkMultimodalAndOpenAiShapes() {
        float[] a = EmbeddingClient.parseEmbeddingVector(Map.of("embedding", List.of(0.1, 0.2, 0.3)));
        assertEquals(3, a.length);
        assertEquals(0.1f, a[0], 1e-6);

        float[] b = EmbeddingClient.parseEmbeddingVector(
                List.of(Map.of("embedding", List.of(1, 2), "index", 0)));
        assertEquals(2, b.length);
        assertEquals(1f, b[0], 1e-6);
    }
}
