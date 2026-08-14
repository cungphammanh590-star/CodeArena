package com.codearena.business.knowledge.embedding;

import com.codearena.business.knowledge.config.KnowledgeProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final KnowledgeProperties properties;

    public float[] embed(String text) {
        String provider = properties.getEmbedding().getProvider();
        if ("http".equalsIgnoreCase(provider)) {
            return embedHttp(text);
        }
        return embedMock(text, properties.getEmbedding().getDim());
    }

    private float[] embedHttp(String text) {
        String url = properties.getEmbedding().getHttpUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("CODEARENA_EMBEDDING_HTTP_URL required when provider=http");
        }
        String model = properties.getEmbedding().getModel();
        String apiKey = properties.getEmbedding().getHttpApiKey();
        RestClient client = RestClient.builder().build();
        Map<String, Object> body = buildHttpBody(model, text, isMultimodal(url, model));
        RestClient.RequestBodySpec req = client.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            req = req.header("Authorization", "Bearer " + apiKey);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = req.body(body).retrieve().body(Map.class);
        if (resp == null) {
            throw new IllegalStateException("empty embedding response");
        }
        return parseEmbeddingVector(resp.get("data"));
    }

    /** 火山 Ark vision embedding 走 /embeddings/multimodal，请求/响应与 OpenAI 文本 embedding 不同。 */
    static boolean isMultimodal(String url, String model) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return u.contains("/embeddings/multimodal") || m.contains("embedding-vision");
    }

    static Map<String, Object> buildHttpBody(String model, String text, boolean multimodal) {
        String inputText = text == null ? "" : text;
        if (multimodal) {
            return Map.of(
                    "model", model,
                    "input", List.of(Map.of("type", "text", "text", inputText)));
        }
        return Map.of("model", model, "input", List.of(inputText));
    }

    static float[] parseEmbeddingVector(Object data) {
        Object emb = null;
        if (data instanceof Map<?, ?> m) {
            // Ark multimodal: { "embedding": [...], "object": "embedding" }
            emb = m.get("embedding");
        } else if (data instanceof List<?> list && !list.isEmpty()) {
            // OpenAI-compatible: [ { "embedding": [...], "index": 0 } ]
            Object first = list.get(0);
            if (first instanceof Map<?, ?> item) {
                emb = item.get("embedding");
            }
        }
        if (emb instanceof List<?> nums) {
            if (!nums.isEmpty() && nums.get(0) instanceof List<?>) {
                nums = (List<?>) nums.get(0);
            }
            float[] out = new float[nums.size()];
            for (int i = 0; i < nums.size(); i++) {
                out[i] = Float.parseFloat(String.valueOf(nums.get(i)));
            }
            return out;
        }
        throw new IllegalStateException("embedding response missing data");
    }

    /** 特征哈希：本地无模型时仍可做粗语义检索演示。 */
    static float[] embedMock(String text, int dim) {
        float[] v = new float[Math.max(8, dim)];
        String normalized = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        List<String> tokens = tokenize(normalized);
        for (String tok : tokens) {
            int h = stableHash(tok);
            int idx = Math.floorMod(h, v.length);
            v[idx] += 1.0f;
            int idx2 = Math.floorMod(h * 31 + 7, v.length);
            v[idx2] += 0.5f;
        }
        // 混入全文 hash 降低全零
        byte[] digest = sha256(normalized);
        for (int i = 0; i < Math.min(digest.length, v.length); i++) {
            v[i] += (digest[i] & 0xff) / 255.0f * 0.01f;
        }
        normalize(v);
        return v;
    }

    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flush(cur, out);
                out.add(new String(Character.toChars(cp)));
                if (i < text.length()) {
                    int cp2 = text.codePointAt(i);
                    if (Character.UnicodeScript.of(cp2) == Character.UnicodeScript.HAN) {
                        out.add(new String(new int[] {cp, cp2}, 0, 2));
                    }
                }
            } else if (Character.isLetterOrDigit(cp)) {
                cur.appendCodePoint(cp);
            } else {
                flush(cur, out);
            }
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static int stableHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        } catch (NoSuchAlgorithmException e) {
            return s.hashCode();
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return new byte[32];
        }
    }

    private static void normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        if (sum <= 1e-12) {
            v[0] = 1f;
            return;
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
    }
}
