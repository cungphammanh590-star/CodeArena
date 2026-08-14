package com.codearena.business.knowledge.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 规则抽取：优先按面试题干（问句行 / 编号题）切分；否则 Markdown 标题；再否则段落窗口。
 */
@Component
public class KnowledgePointExtractor {

    private static final int SHORT_LIMIT = 1500;
    private static final int CHUNK_TARGET = 1200;
    private static final int MIN_BODY = 24;
    private static final int MAX_TITLE = 100;

    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,3}\\s+(.+)$");
    /** 八股常见：1. / 1、 / 一、 题干 */
    private static final Pattern NUMBERED_HEADING =
            Pattern.compile("(?m)^(?:\\d{1,3}|[一二三四五六七八九十百]+)[.、．\\)]\\s*(.+)$");
    /** 显式题目标记：问：/ Q：/ 面试题： */
    private static final Pattern LABELED_QUESTION =
            Pattern.compile("(?m)^(?:问|题目|面试题|Q)\\s*[:：]\\s*(.+)$");
    /**
     * 小林等 PDF 常见结构：独立一行、以？结尾的题干。
     * 限制长度，避免把长段修辞句当标题。
     */
    private static final Pattern QUESTION_LINE =
            Pattern.compile("(?m)^(?=.{4," + MAX_TITLE + "}$)(.+?[？?])\\s*$");

    public record ExtractedKp(String title, String body, String topic, List<String> tags) {}

    public List<ExtractedKp> extract(String titleHint, String cleaned) {
        String text = cleaned == null ? "" : cleaned.trim();
        if (text.isBlank()) {
            return List.of();
        }

        List<ExtractedKp> fromQuestions = splitByQuestionLines(text);
        if (fromQuestions.size() >= 2) {
            return fromQuestions;
        }
        List<ExtractedKp> fromLabeled = splitByPattern(text, LABELED_QUESTION, true);
        if (fromLabeled.size() >= 2) {
            return fromLabeled;
        }
        List<ExtractedKp> fromNumbered = splitByPattern(text, NUMBERED_HEADING, true);
        if (fromNumbered.size() >= 2) {
            return fromNumbered;
        }

        if (text.length() <= SHORT_LIMIT) {
            String title = firstMeaningfulLine(text, titleHint);
            return List.of(new ExtractedKp(title, text, guessTopic(title, text), List.of()));
        }

        List<ExtractedKp> fromHeadings = splitByMarkdownHeadings(text);
        if (fromHeadings.size() >= 2) {
            return fromHeadings;
        }
        return splitByWindows(text, titleHint);
    }

    /** 按「独立问句行」切分；题干作 title，直到下一题干的内容作 body。 */
    private List<ExtractedKp> splitByQuestionLines(String text) {
        Matcher m = QUESTION_LINE.matcher(text);
        List<int[]> heads = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            String raw = m.group(1).trim();
            if (!looksLikeInterviewQuestion(raw)) {
                continue;
            }
            heads.add(new int[] {m.start(), m.end()});
            titles.add(truncateTitle(stripQuestionMark(raw)));
        }
        if (heads.size() < 2) {
            return List.of();
        }
        return buildFromHeads(text, heads, titles, true);
    }

    private List<ExtractedKp> splitByPattern(String text, Pattern pattern, boolean includeHeadingInBody) {
        Matcher m = pattern.matcher(text);
        List<int[]> heads = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            heads.add(new int[] {m.start(), m.end()});
            String t = m.group(1) == null ? m.group().trim() : m.group(1).trim();
            titles.add(truncateTitle(t.isBlank() ? firstMeaningfulLine(m.group(), "知识点") : t));
        }
        if (heads.size() < 2) {
            return List.of();
        }
        return buildFromHeads(text, heads, titles, includeHeadingInBody);
    }

    private List<ExtractedKp> buildFromHeads(
            String text, List<int[]> heads, List<String> titles, boolean includeHeadingInBody) {
        List<ExtractedKp> out = new ArrayList<>();
        for (int i = 0; i < heads.size(); i++) {
            int bodyStart = includeHeadingInBody ? heads.get(i)[0] : heads.get(i)[1];
            int bodyEnd = i + 1 < heads.size() ? heads.get(i + 1)[0] : text.length();
            String body = text.substring(bodyStart, bodyEnd).trim();
            if (body.length() < MIN_BODY) {
                continue;
            }
            String title = titles.get(i);
            out.add(new ExtractedKp(title, body, guessTopic(title, body), List.of("qa")));
        }
        return out.size() >= 2 ? out : List.of();
    }

    private List<ExtractedKp> splitByMarkdownHeadings(String text) {
        Matcher m = MD_HEADING.matcher(text);
        List<int[]> heads = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            heads.add(new int[] {m.start(), m.end()});
            titles.add(m.group(1).trim());
        }
        if (heads.size() < 2) {
            return List.of();
        }
        List<ExtractedKp> out = new ArrayList<>();
        for (int i = 0; i < heads.size(); i++) {
            int bodyStart = heads.get(i)[1];
            int bodyEnd = i + 1 < heads.size() ? heads.get(i + 1)[0] : text.length();
            String body = text.substring(bodyStart, bodyEnd).trim();
            if (body.isBlank()) {
                continue;
            }
            String title = titles.get(i);
            out.add(new ExtractedKp(title, title + "\n\n" + body, guessTopic(title, body), List.of()));
        }
        return out;
    }

    private List<ExtractedKp> splitByWindows(String text, String titleHint) {
        String[] paras = text.split("\\n\\s*\\n");
        List<ExtractedKp> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int part = 1;
        for (String p : paras) {
            String piece = p.trim();
            if (piece.isEmpty()) {
                continue;
            }
            if (buf.length() + piece.length() + 2 > CHUNK_TARGET && buf.length() > 0) {
                String body = buf.toString().trim();
                String title = firstMeaningfulLine(body, titleHint + " (" + part + ")");
                out.add(new ExtractedKp(title, body, guessTopic(title, body), List.of()));
                part++;
                buf.setLength(0);
            }
            if (buf.length() > 0) {
                buf.append("\n\n");
            }
            buf.append(piece);
        }
        if (buf.length() > 0) {
            String body = buf.toString().trim();
            String title = firstMeaningfulLine(body, titleHint + (part > 1 ? " (" + part + ")" : ""));
            out.add(new ExtractedKp(title, body, guessTopic(title, body), List.of()));
        }
        return out;
    }

    /**
     * 过滤明显不是面试题干的问句（过短口号、纯标点、无实质疑问词且过短等）。
     */
    static boolean looksLikeInterviewQuestion(String line) {
        if (line == null) {
            return false;
        }
        String s = line.trim();
        if (s.length() < 4 || s.length() > MAX_TITLE) {
            return false;
        }
        // 去掉结尾问号后仍需有实质内容
        String core = stripQuestionMark(s);
        if (core.length() < 3) {
            return false;
        }
        // 排除纯导航/营销短句
        if (core.matches(".*(扫码|关注公众号|加微信|领取完整版).*")) {
            return false;
        }
        // 含常见题干信号，或本身较短的疑问句（「有错误怎么办？」）
        if (containsQuestionCue(core) || core.length() <= 40) {
            return true;
        }
        return core.endsWith("吗") || core.endsWith("呢") || core.endsWith("什么") || core.endsWith("哪些");
    }

    private static boolean containsQuestionCue(String s) {
        String lower = s.toLowerCase();
        return lower.contains("什么")
                || lower.contains("为什么")
                || lower.contains("怎么")
                || lower.contains("如何")
                || lower.contains("是否")
                || lower.contains("区别")
                || lower.contains("哪些")
                || lower.contains("哪几")
                || lower.contains("原理")
                || lower.contains("优缺点")
                || lower.contains("优势")
                || lower.contains("劣势")
                || lower.contains("有哪些")
                || lower.contains("怎么办")
                || lower.contains("是什么")
                || lower.contains("吗")
                || lower.contains("呢")
                || lower.contains("？")
                || lower.contains("?");
    }

    private static String stripQuestionMark(String s) {
        return s.replaceAll("[？?]+\\s*$", "").trim();
    }

    private static String truncateTitle(String t) {
        if (t.length() <= MAX_TITLE) {
            return t;
        }
        return t.substring(0, MAX_TITLE);
    }

    private static String firstMeaningfulLine(String text, String fallback) {
        for (String line : text.split("\n")) {
            String t = line.trim().replaceFirst("^#+\\s*", "");
            if (t.length() >= 2 && t.length() <= 80) {
                return t;
            }
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return text.substring(0, Math.min(40, text.length())).trim();
    }

    private static String guessTopic(String title, String body) {
        String sample = (title + " " + body).toLowerCase();
        if (sample.contains("锁") || sample.contains("thread") || sample.contains("并发")
                || sample.contains("synchronized") || sample.contains("reentrant")) {
            return "java-concurrency";
        }
        if (sample.contains("jvm") || sample.contains("垃圾回收") || sample.contains("gc")) {
            return "jvm";
        }
        if (sample.contains("mysql") || sample.contains("索引") || sample.contains("事务")) {
            return "mysql";
        }
        if (sample.contains("redis")) {
            return "redis";
        }
        if (sample.contains("spring") || sample.contains("bean")) {
            return "spring";
        }
        if (sample.contains("集合") || sample.contains("hashmap") || sample.contains("arraylist")) {
            return "java-collections";
        }
        return null;
    }
}
