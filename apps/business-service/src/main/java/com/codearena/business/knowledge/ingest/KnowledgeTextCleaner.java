package com.codearena.business.knowledge.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** L1 规则去噪：引流行、页眉页脚、推广句；并合并 PDF 软换行。 */
@Component
public class KnowledgeTextCleaner {

    private static final Pattern PROMO = Pattern.compile(
            "(扫码|扫一扫|微信|微信号|公众号|加群|加我|VX|V信|vx|v信|知识星球|领取资料|关注.*号|加微信|二维码|"
                    + "训练营|1v1私教|点击\\s*了解|求职辅导|无水印|简历模版)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern URL_PROMO = Pattern.compile(
            "(https?://\\S+)|(www\\.\\S+)", Pattern.CASE_INSENSITIVE);

    /** 新段起点：题号 / Markdown 标题 / 显式问答标记 */
    private static final Pattern HARD_BREAK_NEXT = Pattern.compile(
            "^(?:#{1,3}\\s+|\\d{1,3}[.、．\\)]\\s*|[一二三四五六七八九十百]+[.、．]\\s*|"
                    + "(?:问|答|题目|面试题|Q|A)\\s*[:：]).+");

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;…）」》\"']\\s*$");

    public String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        Map<String, Integer> shortCounts = new LinkedHashMap<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.length() > 0 && t.length() <= 40) {
                shortCounts.merge(t, 1, Integer::sum);
            }
        }

        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!kept.isEmpty() && !kept.get(kept.size() - 1).isEmpty()) {
                    kept.add("");
                }
                continue;
            }
            if (PROMO.matcher(t).find()) {
                continue;
            }
            if (t.length() <= 40 && shortCounts.getOrDefault(t, 0) >= 3) {
                continue;
            }
            if (URL_PROMO.matcher(t).find() && (PROMO.matcher(t).find() || t.length() < 80)) {
                continue;
            }
            if (isMostlyNoise(t)) {
                continue;
            }
            kept.add(t);
        }
        return reflowSoftWraps(kept).trim();
    }

    /** 对已成段的文本再跑一遍软换行合并（精炼 answer 用）。 */
    public static String reflowText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> list = new ArrayList<>();
        for (String line : lines) {
            list.add(line.trim());
        }
        return reflowSoftWraps(list);
    }

    /**
     * 合并 PDF 排版产生的软换行：非空行连续且不像新段时拼成同一段。
     * 空行仍作段落分隔。
     */
    static String reflowSoftWraps(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        StringBuilder para = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                flushPara(out, para);
                continue;
            }
            if (para.length() == 0) {
                para.append(line);
                continue;
            }
            if (shouldJoin(para.toString(), line)) {
                para.append(joinGap(para.charAt(para.length() - 1), line.charAt(0))).append(line);
            } else {
                flushPara(out, para);
                para.append(line);
            }
        }
        flushPara(out, para);
        return out.toString().trim();
    }

    private static boolean shouldJoin(String prev, String next) {
        if (HARD_BREAK_NEXT.matcher(next).matches()) {
            return false;
        }
        if (SENTENCE_END.matcher(prev).find()) {
            return false;
        }
        if (prev.length() <= 8 && (next.endsWith("？") || next.endsWith("?"))) {
            return false;
        }
        return true;
    }

    private static String joinGap(char left, char right) {
        if (isCjk(left) || isCjk(right)) {
            return "";
        }
        if (Character.isLetterOrDigit(left) && Character.isLetterOrDigit(right)) {
            return " ";
        }
        if (left == '-' || right == '-') {
            return "";
        }
        return Character.isWhitespace(left) || Character.isWhitespace(right) ? "" : " ";
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HAN
                || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA
                || s == Character.UnicodeScript.HANGUL;
    }

    private static void flushPara(StringBuilder out, StringBuilder para) {
        if (para.length() == 0) {
            return;
        }
        if (out.length() > 0) {
            out.append("\n\n");
        }
        out.append(para);
        para.setLength(0);
    }

    private static boolean isMostlyNoise(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.matches("^[-_=*]{3,}$") || lower.matches("^第?\\d+\\s*页$") || lower.matches("^\\d+$");
    }
}
