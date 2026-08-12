package com.codearena.business.learning.plan.service;

import com.codearena.business.learning.mastery.domain.UserProblemFlagEntity;
import com.codearena.business.learning.mastery.domain.UserProblemFlagRepository;
import com.codearena.business.learning.plan.domain.GoalProblemBankEntity;
import com.codearena.business.learning.plan.domain.GoalProblemBankRepository;
import com.codearena.business.problem.domain.ProblemEntity;
import com.codearena.business.problem.domain.ProblemRepository;
import com.codearena.business.submission.domain.SubmissionRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将用户粘贴的力扣题号/标题解析为题库 problem_id，并标注 Accepted / 掌握。
 *
 * <p>查找顺序：{@code problems} 表 → {@code goal_problem_banks} 元数据；命中 bank
 * 但尚未入库的题目会自动 upsert 到 {@code problems}，便于后续计划/绑题。
 */
@Service
@RequiredArgsConstructor
public class ProblemResolveService {

    private static final Pattern LC_NUM =
            Pattern.compile("(?i)(?:lc\\s*)?(\\d{1,4})(?:\\s*[.、:：)\\-]\\s*|\\s+)");
    private static final Pattern BARE_NUM = Pattern.compile("(?<!\\d)(\\d{1,4})(?!\\d)");
    private static final Pattern PURE_LC = Pattern.compile("(?i)^(?:lc\\s*)?(\\d{1,4})$");

    private final ProblemRepository problemRepository;
    private final GoalProblemBankRepository bankRepository;
    private final SubmissionRepository submissionRepository;
    private final UserProblemFlagRepository flagRepository;

    @Transactional
    public Map<String, Object> resolve(Long userId, List<String> queries, String rawText) {
        List<String> tokens = new ArrayList<>();
        if (queries != null) {
            for (String q : queries) {
                if (q != null && !q.isBlank()) {
                    tokens.add(q.trim());
                }
            }
        }
        if (rawText != null && !rawText.isBlank()) {
            tokens.addAll(extractTokens(rawText));
        }
        tokens = new ArrayList<>(new LinkedHashSet<>(tokens));

        Catalog catalog = loadCatalog();

        List<Map<String, Object>> matched = new ArrayList<>();
        List<Map<String, Object>> ambiguous = new ArrayList<>();
        List<Map<String, Object>> unmatched = new ArrayList<>();
        Set<Integer> matchedIds = new LinkedHashSet<>();
        int fromBank = 0;

        for (String token : tokens) {
            ResolveHit hit = resolveOne(token, catalog);
            if (hit.problem != null && hit.candidates.isEmpty()) {
                ProblemEntity ensured = ensureProblem(hit.problem);
                if (hit.fromBank) {
                    fromBank++;
                }
                if (matchedIds.add(ensured.getProblemId())) {
                    matched.add(brief(ensured, token, hit.fromBank));
                }
            } else if (!hit.candidates.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", token);
                row.put(
                        "candidates",
                        hit.candidates.stream()
                                .map(p -> brief(p, token, false))
                                .collect(Collectors.toList()));
                ambiguous.add(row);
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", token);
                unmatched.add(row);
            }
        }

        Set<Integer> accepted = Set.of();
        Set<Integer> mastered = Set.of();
        if (userId != null && !matchedIds.isEmpty()) {
            accepted = new HashSet<>(submissionRepository.findDistinctProblemIdsByUserIdAndStatusAndProblemIdIn(
                    userId, "Accepted", matchedIds));
            mastered = flagRepository.findByUserIdAndMasteredTrue(userId).stream()
                    .map(UserProblemFlagEntity::getProblemId)
                    .filter(matchedIds::contains)
                    .collect(Collectors.toSet());
        }

        List<Integer> remaining = new ArrayList<>();
        List<Integer> passed = new ArrayList<>();
        for (Integer id : matchedIds) {
            boolean done = accepted.contains(id) || mastered.contains(id);
            if (done) {
                passed.add(id);
            } else {
                remaining.add(id);
            }
            for (Map<String, Object> m : matched) {
                if (id.equals(m.get("problem_id"))) {
                    m.put("accepted", accepted.contains(id));
                    m.put("mastered", mastered.contains(id));
                    m.put("done", done);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("matched", matched);
        data.put("ambiguous", ambiguous);
        data.put("unmatched", unmatched);
        data.put("problem_ids", new ArrayList<>(matchedIds));
        data.put("passed_ids", passed);
        data.put("remaining_ids", remaining);
        data.put("matched_count", matchedIds.size());
        data.put("passed_count", passed.size());
        data.put("remaining_count", remaining.size());
        data.put("resolved_from_bank", fromBank);
        data.put(
                "note",
                "已解析 "
                        + matchedIds.size()
                        + " 题（已刷 "
                        + passed.size()
                        + " / 未刷 "
                        + remaining.size()
                        + (fromBank > 0 ? "；其中 " + fromBank + " 题来自题库元数据并已入库" : "")
                        + "）。"
                        + (ambiguous.isEmpty()
                                ? ""
                                : " 有 " + ambiguous.size() + " 条标题歧义，请让用户确认。")
                        + (unmatched.isEmpty()
                                ? ""
                                : " 有 " + unmatched.size() + " 条未识别（题库暂无）。"));
        return data;
    }

    private Catalog loadCatalog() {
        List<ProblemEntity> all = problemRepository.findAll();
        Map<Integer, ProblemEntity> byId = new HashMap<>();
        Map<String, ProblemEntity> bySlug = new HashMap<>();
        Map<String, ProblemEntity> byTitleExact = new HashMap<>();
        for (ProblemEntity p : all) {
            if (p.getProblemId() != null) {
                byId.put(p.getProblemId(), p);
            }
            if (p.getSlug() != null) {
                bySlug.put(p.getSlug().toLowerCase(Locale.ROOT), p);
            }
            if (p.getTitle() != null) {
                byTitleExact.put(p.getTitle().toLowerCase(Locale.ROOT), p);
            }
        }

        Map<Integer, ProblemEntity> bankById = new HashMap<>();
        Map<String, ProblemEntity> bankBySlug = new HashMap<>();
        Map<String, ProblemEntity> bankByTitle = new HashMap<>();
        for (GoalProblemBankEntity b : bankRepository.findAll()) {
            if (b.getProblemId() == null) {
                continue;
            }
            ProblemEntity virtual = fromBank(b);
            bankById.putIfAbsent(b.getProblemId(), virtual);
            if (b.getSlug() != null) {
                bankBySlug.putIfAbsent(b.getSlug().toLowerCase(Locale.ROOT), virtual);
            }
            if (b.getTitle() != null) {
                bankByTitle.putIfAbsent(b.getTitle().toLowerCase(Locale.ROOT), virtual);
            }
        }
        return new Catalog(byId, bySlug, byTitleExact, all, bankById, bankBySlug, bankByTitle);
    }

    private ProblemEntity fromBank(GoalProblemBankEntity b) {
        ProblemEntity p = new ProblemEntity();
        p.setProblemId(b.getProblemId());
        p.setTitle(b.getTitle() == null || b.getTitle().isBlank()
                ? ("Problem " + b.getProblemId())
                : b.getTitle());
        p.setSlug(b.getSlug() == null || b.getSlug().isBlank()
                ? ("problem-" + b.getProblemId())
                : b.getSlug());
        p.setDifficulty(b.getDifficulty() == null ? "Medium" : b.getDifficulty());
        p.setTags("[]");
        p.setCreatedAt(OffsetDateTime.now());
        return p;
    }

    private ProblemEntity ensureProblem(ProblemEntity candidate) {
        return problemRepository
                .findByProblemId(candidate.getProblemId())
                .orElseGet(() -> {
                    ProblemEntity created = new ProblemEntity();
                    created.setProblemId(candidate.getProblemId());
                    created.setTitle(candidate.getTitle());
                    created.setSlug(candidate.getSlug());
                    created.setDifficulty(candidate.getDifficulty());
                    created.setTags(candidate.getTags() == null ? "[]" : candidate.getTags());
                    created.setCreatedAt(OffsetDateTime.now());
                    return problemRepository.save(created);
                });
    }

    private static List<String> extractTokens(String raw) {
        List<String> out = new ArrayList<>();
        Matcher m = LC_NUM.matcher(raw);
        while (m.find()) {
            out.add(m.group(1));
        }
        if (out.isEmpty()) {
            Matcher b = BARE_NUM.matcher(raw);
            while (b.find()) {
                out.add(b.group(1));
            }
        }
        return out;
    }

    private ResolveHit resolveOne(String token, Catalog catalog) {
        String t = token.trim();
        Matcher num = PURE_LC.matcher(t);
        if (num.matches()) {
            int id = Integer.parseInt(num.group(1));
            ProblemEntity p = catalog.byId.get(id);
            if (p != null) {
                return ResolveHit.exact(p, false);
            }
            ProblemEntity bank = catalog.bankById.get(id);
            if (bank != null) {
                return ResolveHit.exact(bank, true);
            }
            // 纯题号：即便库中暂无元数据，也接受为「已知力扣题号」并生成占位入库
            ProblemEntity placeholder = new ProblemEntity();
            placeholder.setProblemId(id);
            placeholder.setTitle("LC " + id);
            placeholder.setSlug("problem-" + id);
            placeholder.setDifficulty("Medium");
            placeholder.setTags("[]");
            placeholder.setCreatedAt(OffsetDateTime.now());
            return ResolveHit.exact(placeholder, true);
        }

        String lower = t.toLowerCase(Locale.ROOT);
        ProblemEntity byS = catalog.bySlug.get(lower);
        if (byS != null) {
            return ResolveHit.exact(byS, false);
        }
        ProblemEntity bankS = catalog.bankBySlug.get(lower);
        if (bankS != null) {
            return ResolveHit.exact(bankS, true);
        }
        ProblemEntity byT = catalog.byTitleExact.get(lower);
        if (byT != null) {
            return ResolveHit.exact(byT, false);
        }
        ProblemEntity bankT = catalog.bankByTitle.get(lower);
        if (bankT != null) {
            return ResolveHit.exact(bankT, true);
        }

        List<ProblemEntity> cands = catalog.all.stream()
                .filter(p -> p.getTitle() != null && p.getTitle().toLowerCase(Locale.ROOT).contains(lower))
                .limit(5)
                .collect(Collectors.toList());
        if (cands.isEmpty()) {
            cands = catalog.bankByTitle.values().stream()
                    .filter(p -> p.getTitle() != null && p.getTitle().toLowerCase(Locale.ROOT).contains(lower))
                    .limit(5)
                    .collect(Collectors.toList());
        }
        if (cands.size() == 1) {
            boolean fromBank = !catalog.byId.containsKey(cands.get(0).getProblemId());
            return ResolveHit.exact(cands.get(0), fromBank);
        }
        if (cands.size() > 1) {
            return ResolveHit.multi(cands);
        }
        return ResolveHit.none();
    }

    private Map<String, Object> brief(ProblemEntity p, String query, boolean fromBank) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("problem_id", p.getProblemId());
        m.put("title", p.getTitle());
        m.put("slug", p.getSlug());
        m.put("difficulty", p.getDifficulty());
        m.put("query", query);
        m.put("from_bank", fromBank);
        return m;
    }

    private record Catalog(
            Map<Integer, ProblemEntity> byId,
            Map<String, ProblemEntity> bySlug,
            Map<String, ProblemEntity> byTitleExact,
            List<ProblemEntity> all,
            Map<Integer, ProblemEntity> bankById,
            Map<String, ProblemEntity> bankBySlug,
            Map<String, ProblemEntity> bankByTitle) {}

    private record ResolveHit(ProblemEntity problem, List<ProblemEntity> candidates, boolean fromBank) {
        static ResolveHit exact(ProblemEntity p, boolean fromBank) {
            return new ResolveHit(p, List.of(), fromBank);
        }

        static ResolveHit multi(List<ProblemEntity> c) {
            return new ResolveHit(null, c, false);
        }

        static ResolveHit none() {
            return new ResolveHit(null, List.of(), false);
        }
    }
}
