package com.codearena.business.knowledge.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 规则精炼：去掉训练营/番外尾巴，拆出 question/answer；LLM 不可用时的必跑路径。
 */
@Component
public class KnowledgePointRefiner {

    private static final Pattern CUT_TAIL = Pattern.compile(
            "(?is)(?:求职辅导训练营|小林后端开发训练营|小林社招|小林大模型|小林C\\+\\+|小林测试|"
                    + "点击\\s*了解|面试必备|番外篇|我又坚持一年了|写于：\\d{4}|年度总结).*$");

    private static final Pattern PROMO_LINE = Pattern.compile(
            "(扫码|公众号|加微信|训练营|1v1私教|了解\\s*小林|无水印|简历模版)",
            Pattern.CASE_INSENSITIVE);

    public record RefinedKp(
            boolean keep,
            String title,
            String question,
            String answer,
            List<String> keyPoints,
            String topic,
            boolean fromLlm) {}

    public RefinedKp refineRule(KnowledgePointExtractor.ExtractedKp raw) {
        if (raw == null || raw.body() == null || raw.body().isBlank()) {
            return new RefinedKp(false, "", "", "", List.of(), null, false);
        }
        String body = CUT_TAIL.matcher(raw.body().trim()).replaceFirst("").trim();
        StringBuilder cleaned = new StringBuilder();
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!cleaned.isEmpty() && cleaned.charAt(cleaned.length() - 1) != '\n') {
                    cleaned.append('\n');
                }
                continue;
            }
            if (PROMO_LINE.matcher(t).find()) {
                continue;
            }
            if (cleaned.length() > 0) {
                cleaned.append('\n');
            }
            cleaned.append(t);
        }
        String answer = cleaned.toString().trim();
        // 再合并残留软换行（抽取后的块内）
        answer = KnowledgeTextCleaner.reflowText(answer);
        if (answer.length() < 20) {
            return new RefinedKp(false, raw.title(), raw.title(), answer, List.of(), raw.topic(), false);
        }
        String question = raw.title() == null || raw.title().isBlank()
                ? firstLine(answer)
                : raw.title().trim();
        // 去掉答案开头重复的题干行
        String[] lines = answer.split("\n", 2);
        if (lines.length == 2 && normalize(lines[0]).equals(normalize(question))) {
            answer = lines[1].trim();
        } else if (answer.startsWith(question)) {
            answer = answer.substring(question.length()).trim();
            answer = answer.replaceFirst("^[？?：:\\s]+", "");
        }
        if (answer.length() < 16) {
            return new RefinedKp(false, question, question, answer, List.of(), raw.topic(), false);
        }
        List<String> keys = roughKeyPoints(answer);
        String title = question.length() > 120 ? question.substring(0, 120) : question;
        return new RefinedKp(true, title, question, answer, keys, raw.topic(), false);
    }

    private static List<String> roughKeyPoints(String answer) {
        List<String> out = new ArrayList<>();
        for (String p : answer.split("[\\n；;]")) {
            String t = p.trim().replaceAll("^\\d+[\\.、\\)]\\s*", "");
            if (t.length() >= 8 && t.length() <= 80) {
                out.add(t);
            }
            if (out.size() >= 5) {
                break;
            }
        }
        return out;
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.length() >= 2) {
                return t.length() > 120 ? t.substring(0, 120) : t;
            }
        }
        return "知识点";
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[？?\\s]", "");
    }
}
