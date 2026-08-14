package com.codearena.business.knowledge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "codearena.knowledge")
public class KnowledgeProperties {

    private boolean enabled = true;
    private String storageDir = System.getProperty("user.home") + "/.codearena/knowledge";
    /** @deprecated use llmRefineEnabled */
    private boolean llmCleanEnabled = false;
    /** 开启后对每个候选 KP 调 Chat JSON 精炼；失败回退规则。 */
    private boolean llmRefineEnabled = false;
    /** 解析后保留的最大字符数，防止超大八股 PDF OOM。 */
    private int maxRawChars = 200_000;
    /** 单文档最多产出的知识点数量（面试题 PDF 常远超 40）。 */
    private int maxKnowledgePoints = 200;
    private Qdrant qdrant = new Qdrant();
    private Embedding embedding = new Embedding();
    private Llm llm = new Llm();

    @Getter
    @Setter
    public static class Qdrant {
        private String url = "http://127.0.0.1:6333";
        private String collection = "leetmate_user_kb";
        private String apiKey = "";
    }

    @Getter
    @Setter
    public static class Embedding {
        /** mock | http */
        private String provider = "mock";
        private String model = "mock-hash-v1";
        private String version = "1";
        private int dim = 384;
        private String httpUrl = "";
        private String httpApiKey = "";
    }

    @Getter
    @Setter
    public static class Llm {
        /** OpenAI 兼容 chat/completions 完整 URL；空则尝试用户 LLM 设置 */
        private String httpUrl = "";
        private String httpApiKey = "";
        private String model = "";
    }
}
