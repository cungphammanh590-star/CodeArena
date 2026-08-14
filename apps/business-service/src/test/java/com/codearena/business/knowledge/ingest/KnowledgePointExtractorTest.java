package com.codearena.business.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgePointExtractorTest {

    private final KnowledgePointExtractor extractor = new KnowledgePointExtractor();

    @Test
    void splitsInterviewQuestionsEndingWithQuestionMark() {
        String text =
                """
                前言废话若干。

                Java 的优势和劣势是什么？
                Java 跨平台、面向对象，有完善的生态。也有启动慢等缺点。

                Java为什么是跨平台的？
                因为字节码跑在 JVM 上，Write Once Run Anywhere。

                JVM、JDK、JRE三者关系？
                JDK 包含 JRE，JRE 包含 JVM。开发用 JDK，运行用 JRE。
                """;
        List<KnowledgePointExtractor.ExtractedKp> kps = extractor.extract("Java基础", text);
        assertTrue(kps.size() >= 3, "got=" + kps.size());
        assertEquals("Java 的优势和劣势是什么", kps.get(0).title());
        assertTrue(kps.get(0).body().contains("跨平台"));
        assertEquals("Java为什么是跨平台的", kps.get(1).title());
        assertEquals("JVM、JDK、JRE三者关系", kps.get(2).title());
    }

    @Test
    void splitsNumberedBaguStyle() {
        String text =
                """
                1. synchronized 和 ReentrantLock 有什么区别？
                synchronized 是 JVM 关键字；ReentrantLock 是 API 锁。

                2. 什么是公平锁？
                公平锁按等待队列依次获取。
                """;
        List<KnowledgePointExtractor.ExtractedKp> kps = extractor.extract("并发", text);
        assertEquals(2, kps.size());
        assertTrue(kps.get(0).title().contains("synchronized"));
    }

    @Test
    void shortDocStaysSingleWhenNoQuestions() {
        String text = "ReentrantLock 支持可中断加锁。";
        List<KnowledgePointExtractor.ExtractedKp> kps = extractor.extract("笔记", text);
        assertEquals(1, kps.size());
    }
}
