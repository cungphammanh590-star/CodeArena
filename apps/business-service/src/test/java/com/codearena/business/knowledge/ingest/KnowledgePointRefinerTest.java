package com.codearena.business.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgePointRefinerTest {

    private final KnowledgePointRefiner refiner = new KnowledgePointRefiner();

    @Test
    void stripsTrainingCampTail() {
        String body =
                """
                如何设计秒杀场景处理高并发以及超卖现象？
                在数据库层面可以用 for update 与 stock>0 更新。
                也可用 Redis decr + 异步队列。

                求职辅导训练营
                小林后端开发训练营
                点击 了解 小林后端开发训练营
                """;
        KnowledgePointRefiner.RefinedKp r = refiner.refineRule(
                new KnowledgePointExtractor.ExtractedKp("如何设计秒杀场景处理高并发以及超卖现象", body, null, List.of()));
        assertTrue(r.keep());
        assertFalse(r.answer().contains("训练营"));
        assertTrue(r.answer().contains("Redis") || r.answer().contains("for update") || r.answer().contains("stock"));
    }
}
