package com.app.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

/**
 * 四种白名单格式各验一遍。PDF 和 DOCX 的样本在测试里现场生成（pdfbox / poi 已随
 * tika-parsers-standard-package 进入 classpath），不往仓库里塞二进制夹具文件。
 */
class TextExtractorTests {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void 提取纯文本() {
        String text = "第一行\n第二行";

        assertThat(extractor.extract(stream(text), "notes.txt")).contains("第一行", "第二行");
    }

    @Test
    void 提取markdown() {
        String markdown = "# 标题\n\n正文段落。";

        assertThat(extractor.extract(stream(markdown), "readme.md")).contains("标题", "正文段落");
    }

    @Test
    void 提取pdf() throws Exception {
        byte[] pdf = pdfContaining("Hello from PDF");

        assertThat(extractor.extract(new ByteArrayInputStream(pdf), "doc.pdf"))
                .contains("Hello from PDF");
    }

    @Test
    void 提取docx() throws Exception {
        byte[] docx = docxContaining("来自 DOCX 的正文");

        assertThat(extractor.extract(new ByteArrayInputStream(docx), "doc.docx"))
                .contains("来自 DOCX 的正文");
    }

    /**
     * BodyContentHandler 默认只写 100,000 字符就抛异常——一份几百页的 PDF 会直接
     * 撞上。这条用 15 万字符的文本证明限制已被解除，是本类里最容易在重构中被改回去的
     * 一行代码。
     */
    @Test
    void 超过十万字符不被截断() {
        String long_ = "字".repeat(150_000);

        String extracted = extractor.extract(stream(long_), "long.txt");

        assertThat(extracted.chars().filter(c -> c == '字').count()).isEqualTo(150_000);
    }

    /**
     * 损坏的 PDF：{@code %PDF-} 头让 Tika 选中 PDF 解析器，后面的垃圾字节让它解析失败。
     * 这条错误路径是入库流程依赖的——它捕获本异常并把任务标记为 FAILED、phase=EXTRACT，
     * 所以异常信息里必须带文件名，否则用户在处理记录里看不出是哪份文档出的问题。
     */
    @Test
    void 损坏文件抛出带文件名的异常() {
        byte[] brokenPdf = "%PDF-1.4\n这不是合法的 PDF 结构".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(brokenPdf), "broken.pdf"))
                .isInstanceOf(TextExtractionException.class)
                .hasMessageContaining("broken.pdf");
    }

    /**
     * 扩展名只是提示，真正的类型由内容决定。所以一份被误命名为 .pdf 的纯文本仍能正常
     * 提取——这是 Tika 的既有行为，记在这里免得有人以为提取会按扩展名严格校验
     * （格式白名单是上传接口的职责，不是提取器的）。
     */
    @Test
    void 类型按内容探测而不是按扩展名() {
        byte[] plainText = "其实我是纯文本".getBytes(StandardCharsets.UTF_8);

        assertThat(extractor.extract(new ByteArrayInputStream(plainText), "mislabeled.pdf"))
                .contains("其实我是纯文本");
    }

    @Test
    void 白名单只认四种扩展名() {
        assertThat(TextExtractor.isSupported("a.txt")).isTrue();
        assertThat(TextExtractor.isSupported("a.MD")).isTrue();
        assertThat(TextExtractor.isSupported("a.pdf")).isTrue();
        assertThat(TextExtractor.isSupported("a.docx")).isTrue();
        assertThat(TextExtractor.isSupported("a.doc")).isFalse();
        assertThat(TextExtractor.isSupported("a.pptx")).isFalse();
        assertThat(TextExtractor.isSupported("noextension")).isFalse();
        assertThat(TextExtractor.isSupported("trailingdot.")).isFalse();
    }

    private ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] pdfContaining(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] docxContaining(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(out);
            return out.toByteArray();
        }
    }
}
