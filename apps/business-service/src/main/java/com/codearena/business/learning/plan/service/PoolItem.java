package com.codearena.business.learning.plan.service;

/** One candidate problem for plan / list assembly. */
public record PoolItem(
        int problemId,
        String title,
        String slug,
        String difficulty,
        String stageHint,
        int sortOrder) {}
