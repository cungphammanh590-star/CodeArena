package com.codearena.business.learning.plan.service;

import java.util.List;

/** Resolves a goal into an ordered problem pool. */
public interface GoalPoolResolver {

    /** Lowercase goal_type this resolver handles, e.g. company / topic / list. */
    String goalType();

    List<PoolItem> resolve(String goalRef, String difficulty, int limit);
}
