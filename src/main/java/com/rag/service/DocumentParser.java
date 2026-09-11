package com.rag.service;

import com.rag.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档解析器（知识库上传导入）：把上传的 .txt / .md / .doc / .docx / .pdf 解析为
 * 「保留结构的纯文本」——`#` 标题行 + 空行分隔的段落，供 {@link DocumentChunker} 切块。
 *
 * <p>编码策略对齐离线脚本 tools/kb-import/kb_csv_builder.ps1：UTF-8 严格解码，失败回退 GB18030。
 */
@Slf4j
@Component
public class DocumentParser {

    private static final Pattern HEADING_STYLE = Pattern.compile("^(?:heading|标题)([1-9])$");

    /** 支持的文件扩展名（小写，不含点）。 */
    public static boolean isSupported(String fileName) {
        String ext = extensionOf(fileName);
        return "txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)
                || "doc".equals(ext) || "docx".equals(ext) || "pdf".equals(ext);
    }

    /** 提示文案里列出的支持格式（给前端/报错复用）。 */
    public static String supportedFormatsText() {
        return ".txt / .md / .doc / .docx / .pdf";
    }

    /**
     * 解析上传文档。
     *
     * @param fileName 原始文件名（用于判定扩展名）
     * @param bytes    文件字节
     * @return 保留结构的纯文本
     */
    public String parse(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException("文件内容为空");
        }
        String ext = extensionOf(fileName);
        return switch (ext) {
            case "txt", "md", "markdown" -> decodeText(bytes);
            case "docx" -> parseDocx(bytes);
            case "doc" -> parseDoc(bytes);
            case "pdf" -> parsePdf(bytes);
            default -> throw new BizException("暂不支持的文件类型：." + ext + "（支持 " + supportedFormatsText() + "）");
        };
    }

    // ==================== 纯文本 ====================

    /** UTF-8 严格解码，失败回退 GB18030（对齐离线脚本），并剥掉 BOM。 */
    private String decodeText(byte[] bytes) {
        String text = strictDecode(bytes, StandardCharsets.UTF_8);
        if (text == null) {
            text = strictDecode(bytes, Charset.forName("GB18030"));
        }
        if (text == null) {
            log.warn("文档编码既非 UTF-8 也非 GB18030，按 UTF-8 替换解码");
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        return stripBom(text);
    }

    private String strictDecode(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private String stripBom(String text) {
        return (!text.isEmpty() && text.charAt(0) == '﻿') ? text.substring(1) : text;
    }

    // ==================== .docx ====================

    /**
     * 解析 Word：段落逐个成行，样式名映射为 `#`~`######` 标题前缀，表格行用 ` | ` 拼接。
     * 每个段落后补一个空行，使切块器把「Word 段落」当作切块单元（Word 通常不用空行分段）。
     */
    private String parseDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(sb, paragraph);
                } else if (element instanceof XWPFTable table) {
                    appendTable(sb, table);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new BizException("解析 Word 文档失败：" + e.getMessage());
        } catch (RuntimeException e) {
            // POI 对损坏/加密文档常抛运行时异常（如 OfficeXmlFileException）
            throw new BizException("无法解析该 Word 文档（可能已加密或文件损坏）");
        }
    }

    private void appendParagraph(StringBuilder sb, XWPFParagraph paragraph) {
        String text = paragraph.getText();
        text = text == null ? "" : text.replace("\r", "").replace("\n", " ").trim();
        int level = headingLevel(paragraph);
        if (text.isEmpty()) {
            sb.append('\n');
            return;
        }
        if (level > 0) {
            sb.append("#".repeat(level)).append(' ').append(text);
        } else {
            sb.append(text);
        }
        sb.append("\n\n"); // 段落之间留空行，供切块器识别
    }

    /** 样式名 → 标题级别（1~6），非标题返回 0。兼容英文模板 Heading1 与中文模板「标题 1」。 */
    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null || style.isBlank()) {
            return 0;
        }
        String normalized = style.toLowerCase(Locale.ROOT).replace(" ", "").trim();
        Matcher matcher = HEADING_STYLE.matcher(normalized);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        if ("title".equals(normalized)) {
            return 1;
        }
        return 0;
    }

    private void appendTable(StringBuilder sb, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String value = cell.getText();
                cells.add(value == null ? "" : value.replace("\n", " ").trim());
            }
            String line = String.join(" | ", cells).trim();
            if (!line.replace("|", "").isBlank()) {
                sb.append(line).append('\n');
            }
        }
        sb.append('\n');
    }

    // ==================== .doc（老式二进制 Word） ====================

    /**
     * 解析老式二进制 Word（HWPF）。.doc 的样式/结构信息难以可靠映射为标题，
     * 因此退化为「段落文本 + 空行分隔」——靠切块器的段落聚合兜底（无标题时用文件名）。
     */
    private String parseDoc(byte[] bytes) {
        if (startsWith(bytes, "{\\rtf")) {
            throw new BizException("该 .doc 文件实为 RTF 文本格式，请用 Word 另存为真正的 .doc（或改存 .docx）后再试");
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes);
             HWPFDocument doc = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(doc)) {
            StringBuilder sb = new StringBuilder();
            for (String rawParagraph : extractor.getParagraphText()) {
                String text = cleanDocText(rawParagraph);
                if (text.isEmpty()) {
                    sb.append('\n');
                } else {
                    sb.append(text).append("\n\n"); // 段后留空行，供切块器识别
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new BizException("解析 Word(.doc) 文档失败：" + e.getMessage());
        } catch (RuntimeException e) {
            throw new BizException("无法解析该 .doc 文档（可能已加密、损坏，或并非真正的二进制 .doc）");
        }
    }

    /** 清洗 Word 段落：去掉单元格标记(U+0007)、\r、多余空白。 */
    private String cleanDocText(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\u0007", "").replace('\r', ' ').replace('\n', ' ').trim();
        return text.replaceAll("\\s+", " ").trim();
    }

    // ==================== .pdf（文字层提取） ====================

    /** 提取 PDF 文字层。扫描件/图片型 PDF 无文字层，会得到极少文字并给出友好提示。 */
    private String parsePdf(byte[] bytes) {
        try (PDDocument doc = PDDocument.load(bytes)) {
            if (doc.isEncrypted()) {
                throw new BizException("PDF 已加密，请先解除密码保护后再上传");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            // 页分隔符(form feed)→换行；压缩连续空行
            text = text.replace('\f', '\n').replaceAll("\\n{3,}", "\n\n").trim();
            // CJK 字符之间的孤立空格是 PDF 按位置重排的产物，去掉以保持中文连续（提升 embedding 质量）
            text = text.replaceAll("(?<=[\\u3000-\\u9fff])[ \\t]+(?=[\\u3000-\\u9fff])", "");
            long printable = text.chars().filter(c -> !Character.isWhitespace(c)).count();
            if (printable < 20) {
                throw new BizException("未从该 PDF 提取到可检索文字：可能是扫描件/图片型 PDF（暂不支持 OCR），或页面本身为空白");
            }
            return text;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("解析 PDF 失败（文件损坏或已加密）：" + e.getMessage());
        } catch (RuntimeException e) {
            throw new BizException("无法解析该 PDF（文件损坏或已加密）");
        }
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        if (bytes.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (bytes[i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
