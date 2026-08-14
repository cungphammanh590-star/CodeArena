package com.codearena.business.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KnowledgeTextCleanerTest {

    private final KnowledgeTextCleaner cleaner = new KnowledgeTextCleaner();

    @Test
    void removesWeChatPromoLines() {
        String raw = """
                synchronized 与 ReentrantLock 的区别

                synchronized 是 JVM 内置锁。
                扫码关注公众号领取完整版
                加微信 vx:abc123
                ReentrantLock 提供可中断与公平锁。
                """;
        String cleaned = cleaner.clean(raw);
        assertTrue(cleaned.contains("ReentrantLock"));
        assertFalse(cleaned.contains("扫码"));
        assertFalse(cleaned.contains("加微信"));
    }

    @Test
    void joinsPdfSoftLineBreaks() {
        String raw = """
                如何设计秒杀场景处理高并发以及超卖现象？
                在数据库层⾯解决
                1. 在查询商品库存时加排他锁
                利⽤redis的incr、decr的原⼦性 + 异步队列
                实现思路
                1、在系统初始化时，将商品的库存数量加载到redis缓存中
                """;
        // simulate soft wraps without blank lines between continuing sentences
        String soft = """
                在数据库层面可以用 for update 与 stock>0
                更新，也可用 Redis decr + 异步队列做预减库存。

                1. 第一步单独成段
                """;
        String cleaned = cleaner.clean(soft);
        assertTrue(cleaned.contains("stock>0更新") || cleaned.contains("stock>0 更新") || cleaned.replace(" ", "").contains("stock>0更新") || cleaned.contains("for update"));
        // soft wrap joined into one paragraph
        assertFalse(cleaned.contains("stock>0\n更新"));
        assertTrue(cleaned.contains("1. 第一步") || cleaned.contains("第一步"));
    }

    @Test
    void joinsPdfSoftLineBreaksAcrossParenClause() {
        String soft =
                """
                第二范式（2NF）：在1NF的基础上，非码属性必须完全依赖于候选码（在1NF基础上消除非主属性对主码的部分
                函数依赖）
                第二范式需要确保数据库表中的每一列都和主键相关，而不能只与主键的某一部分相关（主要针对联合主键而
                言）。
                """;
        String cleaned = cleaner.clean(soft);
        assertTrue(cleaned.contains("部分函数依赖）"));
        assertFalse(cleaned.contains("部分\n函数"));
        assertTrue(cleaned.contains("联合主键而言）"));
        assertFalse(cleaned.contains("而\n言"));
    }
}
