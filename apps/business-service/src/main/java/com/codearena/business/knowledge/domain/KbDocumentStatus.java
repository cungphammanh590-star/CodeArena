package com.codearena.business.knowledge.domain;

public final class KbDocumentStatus {
    public static final String UPLOADED = "uploaded";
    public static final String PARSING = "parsing";
    public static final String CLEANING = "cleaning";
    public static final String EXTRACTING = "extracting";
    public static final String EMBEDDING = "embedding";
    public static final String READY = "ready";
    public static final String FAILED = "failed";

    private KbDocumentStatus() {}
}
