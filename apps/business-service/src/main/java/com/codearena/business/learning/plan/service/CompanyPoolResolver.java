package com.codearena.business.learning.plan.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CompanyPoolResolver implements GoalPoolResolver {

    private final BankBackedPoolResolver bank;

    @Override
    public String goalType() {
        return "company";
    }

    @Override
    public List<PoolItem> resolve(String goalRef, String difficulty, int limit) {
        String ref = BankBackedPoolResolver.normalizeCompany(goalRef);
        List<PoolItem> items = bank.fromBank("company", ref, difficulty, limit);
        if (items.isEmpty() && !"Google".equals(ref)) {
            // soft fallback demo bank
            items = bank.fromBank("company", "Google", difficulty, limit);
        }
        return items;
    }
}
