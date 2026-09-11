package com.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档切块器：把「保留结构的纯文本」切成知识片段，规则移植自离线脚本
 * {@code tools/kb-import/kb_csv_builder.ps1}（ConvertTo-KbChunks）：
 *
 * <ul>
 *   <li>{@code #}~{@code ######} 行为块边界，其文字作为该块标题（清洗后截断 200 字）</li>
 *   <li>正文按空行拆段落；图片/分隔线/HTML 注释/代码围栏等噪音行整行忽略</li>
 *   <li>贪心聚合：当前缓冲 &ge; {@link #MIN_CHARS} 且再加一段会超 {@link #MAX_CHARS} 时截断成一条</li>
 *   <li>按内容精确去重（忽略大小写）；短于 {@link #MIN_KEEP_CHARS} 的噪声块丢弃</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentChunker {

    /** 单条片段目标下限（字）。 */
    public static final int MIN_CHARS = 150;
    /** 单条片段目标上限（字）。 */
    public static final int MAX_CHARS = 500;
    /** 短于此长度的块视为噪声（页脚/落款等）丢弃。 */
    public static final int MIN_KEEP_CHARS = 20;
    /** 标题截断长度，对齐 knowledge_chunk.title VARCHAR(200)。 */
    private static final int MAX_TITLE_CHARS = 200;

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern NOISE_IMAGE = Pattern.compile("^!\\[.*]\\(.*\\)$");
    private static final Pattern NOISE_RULE = Pattern.compile("^[-*_]{3,}$");
    private static final Pattern NOISE_COMMENT = Pattern.compile("^<!--.*-->$");
    private static final Pattern NOISE_FENCE = Pattern.compile("^(```|~~~).*$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern INLINE_MARK = Pattern.compile("[`*_~]");

    /** 切块结果：标题 + 正文（正文为原文段落原样拼接）。 */
    public record Chunk(String title, String content) {
    }

    /**
     * 切块。
     *
     * @param text          保留结构的纯文本（{@code #} 标题行 + 空行分段）
     * @param fallbackTitle 无标题时的兜底标题（通常传文件名）
     */
    public List<Chunk> chunk(String text, String fallbackTitle) {
        List<Chunk> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        Set<String> seen = new HashSet<>();
        List<String> buffer = new ArrayList<>();
        int bufferLen = 0;
        String currentTitle = fallbackTitle;

        for (Item item : tokenize(text)) {
            if (item.heading) {
                flush(result, seen, buffer, currentTitle, fallbackTitle);
                bufferLen = 0;
                currentTitle = cleanTitle(item.text, fallbackTitle);
                continue;
            }
            // 超长段落（> MAX_CHARS）先安全切小，避免 .pdf/.doc 这类无标题结构的文档产出巨型片段
            for (String part : splitParagraph(item.text, MAX_CHARS)) {
                if (!buffer.isEmpty() && bufferLen >= MIN_CHARS && bufferLen + part.length() > MAX_CHARS) {
                    flush(result, seen, buffer, currentTitle, fallbackTitle);
                    bufferLen = 0;
                }
                buffer.add(part);
                bufferLen += part.length();
            }
        }
        flush(result, seen, buffer, currentTitle, fallbackTitle);
        return result;
    }

    // ==================== 内部 ====================

    /** 把文本切成「标题项 / 段落项」的有序序列。 */
    private List<Item> tokenize(String text) {
        List<Item> items = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                addParagraph(items, paragraph);
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                addParagraph(items, paragraph);
                items.add(new Item(true, heading.group(1)));
                continue;
            }
            if (isNoise(line)) {
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append('\n');
            }
            paragraph.append(line);
        }
        addParagraph(items, paragraph);
        return items;
    }

    private void addParagraph(List<Item> items, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            items.add(new Item(false, paragraph.toString()));
            paragraph.setLength(0);
        }
    }

    /** 把当前缓冲落成一条片段（过短/重复则丢弃）。 */
    private void flush(List<Chunk> out, Set<String> seen, List<String> buffer,
                       String title, String fallbackTitle) {
        if (buffer.isEmpty()) {
            return;
        }
        String content = String.join("\n", buffer).trim();
        buffer.clear();
        if (content.length() < MIN_KEEP_CHARS) {
            return;
        }
        if (!seen.add(content.toLowerCase(Locale.ROOT))) {
            return; // 同一文档内内容重复，只留第一条
        }
        String finalTitle = (title == null || title.isBlank()) ? fallbackTitle : title;
        out.add(new Chunk(finalTitle == null ? "" : finalTitle, content));
    }

    /** 标题清洗：去加粗/行内标记、压缩空白、截断 200 字。 */
    private String cleanTitle(String raw, String fallbackTitle) {
        if (raw == null) {
            return fallbackTitle;
        }
        String title = BOLD.matcher(raw).replaceAll("$1");
        title = INLINE_MARK.matcher(title).replaceAll("");
        title = title.replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) {
            return fallbackTitle;
        }
        return title.length() > MAX_TITLE_CHARS ? title.substring(0, MAX_TITLE_CHARS) : title;
    }

    /**
     * 把超长段落切分成 ≤ max 的若干段：优先在换行处断（PDF 换行即视觉行，重拼后近似原文段落）；
     * 仅当单行本身也超长时才做字符硬切。长度 ≤ max 的段落原样返回。
     */
    private List<String> splitParagraph(String paragraph, int max) {
        if (paragraph.length() <= max) {
            return List.of(paragraph);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : paragraph.split("\n", -1)) {
            String piece = line.trim();
            if (piece.isEmpty()) {
                if (cur.length() > 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            while (piece.length() > max) { // 单行过长 → 硬切
                if (cur.length() > 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
                parts.add(piece.substring(0, max));
                piece = piece.substring(max);
            }
            if (cur.length() > 0 && cur.length() + 1 + piece.length() > max) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(piece);
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private boolean isNoise(String line) {
        return NOISE_IMAGE.matcher(line).matches()
                || NOISE_RULE.matcher(line).matches()
                || NOISE_COMMENT.matcher(line).matches()
                || NOISE_FENCE.matcher(line).matches();
    }

    private static final class Item {
        final boolean heading;
        final String text;

        Item(boolean heading, String text) {
            this.heading = heading;
            this.text = text;
        }
    }
}
