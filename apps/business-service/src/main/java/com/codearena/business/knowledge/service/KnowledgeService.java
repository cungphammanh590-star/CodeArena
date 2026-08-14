package com.codearena.business.knowledge.service;

import com.codearena.business.knowledge.config.KnowledgeProperties;
import com.codearena.business.knowledge.domain.KbDocumentEntity;
import com.codearena.business.knowledge.domain.KbDocumentRepository;
import com.codearena.business.knowledge.domain.KbDocumentStatus;
import com.codearena.business.knowledge.domain.KbEmbeddingEntity;
import com.codearena.business.knowledge.domain.KbEmbeddingRepository;
import com.codearena.business.knowledge.domain.KbKnowledgePointEntity;
import com.codearena.business.knowledge.domain.KbKnowledgePointRepository;
import com.codearena.business.knowledge.embedding.EmbeddingClient;
import com.codearena.business.knowledge.ingest.KnowledgeTextCleaner;
import com.codearena.business.knowledge.qdrant.QdrantClient;
import com.codearena.business.knowledge.srs.KpSrsService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeProperties properties;
    private final KbDocumentRepository documentRepository;
    private final KbKnowledgePointRepository kpRepository;
    private final KbEmbeddingRepository embeddingRepository;
    private final KnowledgeIngestService ingestService;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final KpSrsService kpSrsService;

    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge base disabled");
        }
    }

    @Transactional
    public Map<String, Object> createTextDocument(
            Long userId, String title, String sourceType, String content) {
        requireEnabled();
        String type = normalizeSourceType(sourceType, false);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content required");
        }
        String resolvedTitle =
                (title == null || title.isBlank()) ? defaultTitle(content) : title.trim();

        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setUserId(userId);
        doc.setTitle(resolvedTitle);
        doc.setSourceType(type);
        doc.setRawText(content);
        doc.setContentHash(sha256(content));
        doc.setStatus(KbDocumentStatus.UPLOADED);
        doc = documentRepository.save(doc);
        scheduleIngest(doc.getId());
        return toDocumentView(doc, false);
    }

    @Transactional
    public Map<String, Object> createPdfDocument(Long userId, String title, MultipartFile file)
            throws Exception {
        requireEnabled();
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file required");
        }
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("upload.pdf");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only .pdf supported");
        }
        byte[] bytes = file.getBytes();
        if (bytes.length < 5 || !(bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F')) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid pdf header");
        }

        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setUserId(userId);
        doc.setTitle((title == null || title.isBlank()) ? name : title.trim());
        doc.setSourceType("pdf");
        doc.setContentHash(sha256(bytes));
        doc.setStatus(KbDocumentStatus.UPLOADED);
        doc = documentRepository.save(doc);

        Path dir = Path.of(properties.getStorageDir(), String.valueOf(userId));
        Files.createDirectories(dir);
        Path path = dir.resolve(doc.getId() + ".pdf");
        Files.write(path, bytes);
        doc.setStoragePath(path.toAbsolutePath().toString());
        documentRepository.save(doc);

        scheduleIngest(doc.getId());
        return toDocumentView(doc, false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDocuments(Long userId) {
        requireEnabled();
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(d -> toDocumentView(d, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDocument(Long userId, Long id) {
        requireEnabled();
        KbDocumentEntity doc = documentRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found"));
        return toDocumentView(doc, true);
    }

    @Transactional
    public void deleteDocument(Long userId, Long id) {
        requireEnabled();
        KbDocumentEntity doc = documentRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found"));
        deleteVectorsForDocument(doc.getId());
        documentRepository.delete(doc);
        if (doc.getStoragePath() != null) {
            try {
                Files.deleteIfExists(Path.of(doc.getStoragePath()));
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    /** Delete non-relational private data before the users row is physically removed. */
    @Transactional
    public void purgeExternalAssets(Long userId) {
        List<KbDocumentEntity> documents = documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (KbDocumentEntity document : documents) {
            deleteVectorsForDocument(document.getId());
            if (document.getStoragePath() != null && !document.getStoragePath().isBlank()) {
                try {
                    Files.deleteIfExists(Path.of(document.getStoragePath()));
                } catch (Exception ex) {
                    throw new IllegalStateException("failed to delete private knowledge file", ex);
                }
            }
        }
        Path userDir = Path.of(properties.getStorageDir(), String.valueOf(userId));
        try {
            if (Files.exists(userDir)) {
                try (var paths = Files.walk(userDir)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to delete private knowledge directory", ex);
        }
    }

    @Transactional
    public Map<String, Object> reprocess(Long userId, Long id) {
        requireEnabled();
        KbDocumentEntity doc = documentRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found"));
        doc.setStatus(KbDocumentStatus.UPLOADED);
        doc.setFailureReason(null);
        documentRepository.save(doc);
        scheduleIngest(doc.getId());
        return toDocumentView(doc, false);
    }

    /** 必须在事务提交后再异步摄入，否则 worker 读不到刚写入的文档。 */
    private void scheduleIngest(Long documentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ingestService.processAsync(documentId);
                }
            });
        } else {
            ingestService.processAsync(documentId);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listKps(Long userId) {
        requireEnabled();
        return kpRepository
                .findByUserIdAndStatusOrderByCreatedAtDesc(userId, KbKnowledgePointEntity.STATUS_READY)
                .stream()
                .map(kp -> {
                    String sourceTitle = documentRepository
                            .findById(kp.getDocumentId())
                            .map(KbDocumentEntity::getTitle)
                            .orElse(null);
                    return toKpView(kp, sourceTitle, false);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getKp(Long userId, Long id) {
        requireEnabled();
        KbKnowledgePointEntity kp = kpRepository
                .findByIdAndUserIdAndStatus(id, userId, KbKnowledgePointEntity.STATUS_READY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge point not found"));
        String sourceTitle = documentRepository
                .findById(kp.getDocumentId())
                .map(KbDocumentEntity::getTitle)
                .orElse(null);
        return toKpView(kp, sourceTitle, true);
    }

    @Transactional
    public void deleteKp(Long userId, Long id) {
        requireEnabled();
        KbKnowledgePointEntity kp = kpRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge point not found"));
        deleteVectorsForKp(kp.getId());
        kpSrsService.removeForKp(kp.getId());
        kp.setStatus(KbKnowledgePointEntity.STATUS_DELETED);
        kpRepository.save(kp);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> search(Long userId, String query, String topic, int limit) {
        requireEnabled();
        if (query == null || query.isBlank()) {
            return Map.of("items", List.of(), "count", 0);
        }
        float[] vector = embeddingClient.embed(query);
        List<QdrantClient.SearchHit> hits = qdrantClient.search(vector, userId, topic, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (QdrantClient.SearchHit hit : hits) {
            Optional<KbKnowledgePointEntity> kpOpt = kpRepository.findByIdAndUserIdAndStatus(
                    hit.kpId(), userId, KbKnowledgePointEntity.STATUS_READY);
            if (kpOpt.isEmpty()) {
                continue;
            }
            KbKnowledgePointEntity kp = kpOpt.get();
            String sourceTitle = documentRepository
                    .findById(kp.getDocumentId())
                    .map(KbDocumentEntity::getTitle)
                    .orElse(null);
            Map<String, Object> view = toKpView(kp, sourceTitle, false);
            view.put("score", hit.score());
            view.put("snippet", snippet(kp.getBody(), 240));
            items.add(view);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("count", items.size());
        return out;
    }

    private void deleteVectorsForDocument(Long documentId) {
        for (KbKnowledgePointEntity kp : kpRepository.findByDocumentId(documentId)) {
            deleteVectorsForKp(kp.getId());
        }
    }

    private void deleteVectorsForKp(Long kpId) {
        List<String> ids = new ArrayList<>();
        for (KbEmbeddingEntity emb :
                embeddingRepository.findByKnowledgePointIdAndStatus(kpId, KbEmbeddingEntity.STATUS_ACTIVE)) {
            emb.setStatus(KbEmbeddingEntity.STATUS_STALE);
            embeddingRepository.save(emb);
            ids.add(emb.getQdrantPointId());
        }
        qdrantClient.deletePoints(ids);
    }

    private static String normalizeSourceType(String sourceType, boolean pdf) {
        if (pdf) {
            return "pdf";
        }
        if (sourceType == null || sourceType.isBlank()) {
            return "text";
        }
        String t = sourceType.trim().toLowerCase(Locale.ROOT);
        if (!t.equals("text") && !t.equals("markdown") && !t.equals("md")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source_type must be text|markdown");
        }
        return t.equals("md") ? "markdown" : t;
    }

    private static String defaultTitle(String content) {
        for (String line : content.split("\n")) {
            String t = line.trim().replaceFirst("^#+\\s*", "");
            if (t.length() >= 2) {
                return t.length() > 80 ? t.substring(0, 80) : t;
            }
        }
        return "未命名笔记";
    }

    public Map<String, Object> toDocumentView(KbDocumentEntity doc, boolean includeText) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.getId());
        m.put("title", doc.getTitle());
        m.put("source_type", doc.getSourceType());
        m.put("status", doc.getStatus());
        m.put("failure_reason", doc.getFailureReason());
        m.put("created_at", doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
        m.put("updated_at", doc.getUpdatedAt() == null ? null : doc.getUpdatedAt().toString());
        if (includeText) {
            m.put("raw_text", doc.getRawText());
            m.put("cleaned_text", doc.getCleanedText());
        }
        return m;
    }

    public Map<String, Object> toKpView(KbKnowledgePointEntity kp, String sourceTitle, boolean fullBody) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kp.getId());
        m.put("document_id", kp.getDocumentId());
        m.put("title", kp.getTitle());
        m.put("topic", kp.getTopic());
        m.put("tags_json", kp.getTagsJson());
        m.put("version", kp.getVersion());
        m.put("refined", Boolean.TRUE.equals(kp.getRefined()));
        m.put("source_title", sourceTitle);
        String question =
                kp.getQuestion() != null && !kp.getQuestion().isBlank() ? kp.getQuestion() : kp.getTitle();
        m.put("question", KnowledgeTextCleaner.reflowText(question));
        if (fullBody) {
            String body = kp.getBody() == null ? "" : KnowledgeTextCleaner.reflowText(kp.getBody());
            String answer = kp.getAnswer() != null && !kp.getAnswer().isBlank()
                    ? KnowledgeTextCleaner.reflowText(kp.getAnswer())
                    : body;
            m.put("body", body);
            m.put("answer", answer);
            m.put("key_points_json", kp.getKeyPointsJson());
        } else {
            String previewSrc =
                    kp.getAnswer() != null && !kp.getAnswer().isBlank() ? kp.getAnswer() : kp.getBody();
            m.put("body_preview", snippet(KnowledgeTextCleaner.reflowText(previewSrc), 160));
        }
        return m;
    }

    private static String snippet(String body, int max) {
        if (body == null) {
            return "";
        }
        String t = body.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return "";
        }
    }
}
