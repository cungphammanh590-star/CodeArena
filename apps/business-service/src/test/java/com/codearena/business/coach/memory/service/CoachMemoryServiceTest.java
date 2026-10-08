package com.codearena.business.coach.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.codearena.business.coach.memory.domain.UserCoachMemoryEntity;
import com.codearena.business.coach.memory.domain.UserCoachMemoryRepository;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CoachMemoryServiceTest {

    @Test
    void sameStableKeyConsolidatesEvidenceInsteadOfAppending() {
        UserCoachMemoryEntity existing = memory(7L, "weakness", "旧结论", 206, 0.7f);
        existing.setMemoryKey("skill:linked-list:pointer-order");
        existing.setEvidenceCount(2);
        AtomicReference<UserCoachMemoryEntity> savedRow = new AtomicReference<>();
        UserCoachMemoryRepository repository = repository((method, args) -> switch (method) {
            case "findByUserIdAndKindAndMemoryKeyAndActiveTrue" -> Optional.of(existing);
            case "save" -> {
                savedRow.set((UserCoachMemoryEntity) args[0]);
                yield args[0];
            }
            default -> null;
        });
        CoachMemoryService service = new CoachMemoryService(repository);

        UserCoachMemoryEntity saved = service.remember(
                42L,
                "weakness",
                "两次混淆 prev 与 next 的更新顺序",
                "coach",
                206,
                0.9f,
                "SKILL:Linked-List:Pointer-Order",
                "一级提示后自行修正",
                "session-1",
                30);

        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getEvidenceCount()).isEqualTo(3);
        assertThat(saved.getContent()).contains("prev");
        assertThat(savedRow.get()).isSameAs(existing);
        assertThat(saved.getLastEvidence()).isEqualTo("一级提示后自行修正");
        assertThat(saved.getExpiresAt()).isAfter(OffsetDateTime.now().plusDays(29));
    }

    @Test
    void recallFiltersExpiredAndRanksCurrentProblemFirst() {
        UserCoachMemoryEntity general = memory(1L, "goal", "准备面试", null, 1f);
        general.setSource("user");
        UserCoachMemoryEntity relevant = memory(2L, "weakness", "链表指针顺序", 206, 0.7f);
        UserCoachMemoryEntity expired = memory(3L, "weakness", "过时结论", 206, 1f);
        expired.setExpiresAt(OffsetDateTime.now().minusDays(1));
        UserCoachMemoryRepository repository = repository((method, args) ->
                "findByUserIdAndActiveTrueOrderByUpdatedAtDesc".equals(method)
                        ? List.of(general, expired, relevant)
                        : null);
        CoachMemoryService service = new CoachMemoryService(repository);

        List<UserCoachMemoryEntity> recalled = service.recall(42L, null, 206, 10);

        assertThat(recalled).extracting(UserCoachMemoryEntity::getId).containsExactly(2L, 1L);
    }

    private interface RepositoryCall {
        Object invoke(String method, Object[] args);
    }

    private static UserCoachMemoryRepository repository(RepositoryCall call) {
        return (UserCoachMemoryRepository) Proxy.newProxyInstance(
                UserCoachMemoryRepository.class.getClassLoader(),
                new Class<?>[] {UserCoachMemoryRepository.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "MemoryRepositoryStub";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return call.invoke(method.getName(), args == null ? new Object[0] : args);
                });
    }

    private static UserCoachMemoryEntity memory(
            Long id, String kind, String content, Integer problemId, float confidence) {
        UserCoachMemoryEntity row = new UserCoachMemoryEntity();
        row.setId(id);
        row.setUserId(42L);
        row.setKind(kind);
        row.setContent(content);
        row.setProblemId(problemId);
        row.setConfidence(confidence);
        row.setSource("coach");
        row.setActive(true);
        row.setEvidenceCount(1);
        row.setUpdatedAt(OffsetDateTime.now());
        row.setLastConfirmedAt(OffsetDateTime.now());
        return row;
    }
}
