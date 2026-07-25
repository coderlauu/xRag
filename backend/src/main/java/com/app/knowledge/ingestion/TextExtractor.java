package com.app.knowledge.ingestion;

import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

/**
 * 用 Apache Tika 把文件读成纯文本。一个依赖覆盖全部格式，不必为每种格式各引一个库。
 *
 * <p>见 tech/knowledge-base/architecture.md §5。
 */
@Component
public class TextExtractor {

    /**
     * 第一版格式白名单。上传接口据此在入口就拒绝（返回 415），不等到分块阶段才失败——
     * 那时文件已经上传完、任务已经创建，用户要等到处理失败才知道格式不行。
     */
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "pdf", "docx");

    /**
     * @param fileName 原始文件名。传给 Tika 作为类型探测的提示——单看字节流时
     *                 {@code .md} 和 {@code .txt} 无从区分，有文件名会更准
     */
    public String extract(InputStream stream, String fileName) {
        // BodyContentHandler 的无参构造默认只写 100,000 个字符，超出直接抛
        // WriteLimitReachedException。对一份几百页的 PDF 来说这是静默截断级别的坑，
        // 必须显式传 -1 解除限制。代价是整篇文本进堆——这是分块本身就要求的，
        // 分块算法需要看到全文。
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try {
            new AutoDetectParser().parse(stream, handler, metadata, new ParseContext());
        } catch (Exception exception) {
            throw new TextExtractionException(
                    "无法从 %s 提取文本：%s".formatted(fileName, exception.toString()), exception);
        }
        return handler.toString();
    }

    /** 扩展名是否在白名单内。无扩展名一律不支持。 */
    public static boolean isSupported(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
