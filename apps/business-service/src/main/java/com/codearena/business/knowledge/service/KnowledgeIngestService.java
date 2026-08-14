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
import com.codearena.business.knowledge.ingest.KnowledgeLlmRefiner;
import com.codearena.business.knowledge.ingest.KnowledgePointExtractor;
import com.codearena.business.knowledge.ingest.KnowledgePointRefiner;
import com.codearena.business.knowledge.ingest.KnowledgeTextCleaner;
import com.codearena.business.knowledge.ingest.PdfTextExtractor;
import com.codearena.business.knowledge.qdrant.QdrantClient;
import com.codearena.business.knowledge.srs.KpSrsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIngestService {

    private final KbDocumentRepository documentRepository;
    private final KbKnowledgePointRepository kpRepository;
    private final KbEmbeddingRepository embeddingRepository;
    private final KnowledgeTextCleaner cleaner;
    private final KnowledgePointExtractor extractor;
    private final KnowledgeLlmRefiner llmRefiner;
    private final PdfTextExtractor pdfTextExtractor;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final KpSrsService kpSrsService;
    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    @Async
    @Transactional
    public void processAsync(Long documentId) {
        try {
            runPipeline(documentId);
        } catch (Exception e) {
            log.warn("ingest failed doc={}: {}", documentId, e.toString());
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(KbDocumentStatus.FAILED);
                doc.setFailureReason(trim(e.getMessage() == null ? e.toString() : e.getMessage(), 2000));
                documentRepository.save(doc);
            });
        }
    }

    private void runPipeline(Long documentId) throws Exception {
        KbDocumentEntity doc = documentRepository
                .findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("document not found"));

        setStatus(doc, KbDocumentStatus.PARSING, null);
        String raw = parseRaw(doc);
        if (raw != null && raw.length() > properties.getMaxRawChars()) {
            raw = raw.substring(0, properties.getMaxRawChars())
                    + "\n\n... (truncated, over max_raw_chars)";
        }
        doc.setRawText(raw);
        documentRepository.save(doc);

        if (raw == null
                || raw.isBlank()
                || ("pdf".equals(doc.getSourceType()) && !PdfTextExtractor.looksLikeText(raw))) {
            throw new IllegalStateException(
                    "无可抽取文本（扫描 PDF 请改用 Text/Markdown，或提供可复制文本的 PDF）");
        }

        setStatus(doc, KbDocumentStatus.CLEANING, null);
        String cleaned = cleaner.clean(raw);
        doc.setCleanedText(cleaned);
        documentRepository.save(doc);
        if (cleaned.isBlank()) {
            throw new IllegalStateException("清洗后文本为空");
        }

        setStatus(doc, KbDocumentStatus.EXTRACTING, null);
        retireExistingPoints(doc);

        List<KnowledgePointExtractor.ExtractedKp> extracted = extractor.extract(doc.getTitle(), cleaned);
        if (extracted.isEmpty()) {
            throw new IllegalStateException("未能抽取知识点");
        }
        int maxKp = Math.max(1, properties.getMaxKnowledgePoints());
        if (extracted.size() > maxKp) {
            extracted = extracted.subList(0, maxKp);
        }

        List<KbKnowledgePointEntity> kps = new ArrayList<>();
        for (KnowledgePointExtractor.ExtractedKp item : extracted) {
            KnowledgePointRefiner.RefinedKp refined = llmRefiner.refine(doc.getUserId(), item);
            if (!refined.keep()) {
                continue;
            }
            KbKnowledgePointEntity kp = new KbKnowledgePointEntity();
            kp.setDocumentId(doc.getId());
            kp.setUserId(doc.getUserId());
            kp.setTitle(trim(refined.title(), 512));
            String body = refined.question() + "\n\n" + refined.answer();
            if (refined.keyPoints() != null && !refined.keyPoints().isEmpty()) {
                body = body + "\n\n要点：\n- " + String.join("\n- ", refined.keyPoints());
            }
            kp.setBody(body);
            kp.setQuestion(trim(refined.question(), 2000));
            kp.setAnswer(refined.answer());
            kp.setKeyPointsJson(toJson(refined.keyPoints()));
            kp.setRefined(refined.fromLlm());
            kp.setTopic(refined.topic());
            kp.setTagsJson(toJson(item.tags()));
            kp.setStatus(KbKnowledgePointEntity.STATUS_READY);
            kp.setVersion(1);
            kps.add(kpRepository.save(kp));
        }
        if (kps.isEmpty()) {
            throw new IllegalStateException("精炼后无可用知识点（可能全是噪声）");
        }

        setStatus(doc, KbDocumentStatus.EMBEDDING, null);
        String model = properties.getEmbedding().getModel();
        String version = properties.getEmbedding().getVersion();
        for (KbKnowledgePointEntity kp : kps) {
            String embedText = embedText(kp);
            float[] vector = embeddingClient.embed(embedText);
            String pointId = qdrantClient.upsert(
                    UUID.randomUUID().toString(),
                    vector,
                    kp.getUserId(),
                    kp.getId(),
                    doc.getId(),
                    kp.getTopic(),
                    kp.getVersion());
            KbEmbeddingEntity emb = new KbEmbeddingEntity();
            emb.setKnowledgePointId(kp.getId());
            emb.setEmbeddingModel(model);
            emb.setEmbeddingVersion(version);
            emb.setQdrantPointId(pointId);
            emb.setStatus(KbEmbeddingEntity.STATUS_ACTIVE);
            embeddingRepository.save(emb);
            kpSrsService.enroll(kp.getUserId(), kp.getId());
        }

        setStatus(doc, KbDocumentStatus.READY, null);
    }

    private static String embedText(KbKnowledgePointEntity kp) {
        StringBuilder sb = new StringBuilder();
        if (kp.getTitle() != null) {
            sb.append(kp.getTitle()).append('\n');
        }
        if (kp.getQuestion() != null) {
            sb.append(kp.getQuestion()).append('\n');
        }
        if (kp.getAnswer() != null) {
            sb.append(kp.getAnswer());
        } else if (kp.getBody() != null) {
            sb.append(kp.getBody());
        }
        return sb.toString();
    }

    private void retireExistingPoints(KbDocumentEntity doc) {
        List<KbKnowledgePointEntity> existing = kpRepository.findByDocumentId(doc.getId());
        List<String> pointIds = new ArrayList<>();
        for (KbKnowledgePointEntity kp : existing) {
            for (KbEmbeddingEntity emb : embeddingRepository.findByKnowledgePointIdAndStatus(
                    kp.getId(), KbEmbeddingEntity.STATUS_ACTIVE)) {
                emb.setStatus(KbEmbeddingEntity.STATUS_STALE);
                embeddingRepository.save(emb);
                pointIds.add(emb.getQdrantPointId());
            }
            kp.setStatus(KbKnowledgePointEntity.STATUS_DELETED);
            kpRepository.save(kp);
            kpSrsService.removeForKp(kp.getId());
        }
        qdrantClient.deletePoints(pointIds);
    }

    private String parseRaw(KbDocumentEntity doc) throws Exception {
        if ("pdf".equals(doc.getSourceType())) {
            if (doc.getStoragePath() == null || doc.getStoragePath().isBlank()) {
                throw new IllegalStateException("PDF 缺少存储路径");
            }
            return pdfTextExtractor.extractFromPath(Path.of(doc.getStoragePath()));
        }
        if (doc.getRawText() != null && !doc.getRawText().isBlank()) {
            return doc.getRawText();
        }
        if (doc.getStoragePath() != null) {
            return Files.readString(Path.of(doc.getStoragePath()));
        }
        throw new IllegalStateException("文档无正文");
    }

    private void setStatus(KbDocumentEntity doc, String status, String reason) {
        doc.setStatus(status);
        doc.setFailureReason(reason);
        documentRepository.save(doc);
    }

    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
