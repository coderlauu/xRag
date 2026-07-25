package com.app.knowledge.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 把远程 URL 抓到本地临时文件。
 *
 * <p>**必须先落本地临时文件再上传对象存储，不能边下边传。** 远程响应的
 * {@code Content-Length} 不可信——可能缺失、可能是 chunked、也可能干脆撒谎；只有真正写完
 * 文件才知道实际大小，而大小校验和内容哈希都依赖这个实际值。有了本地副本，后续重新分块
 * 也不必再下一次（与 FILE 来源共用同一条执行链路）。
 */
@Component
public class RemoteFetcher {

    /** 抓取结果。{@code etag} / {@code lastModified} 供工单 17 的两级变更检测使用。 */
    public record Fetched(Path file, long size, String contentType, String etag, String lastModified) {}

    /** 抓取失败一律用它，调用方翻译成 {@code 400} 并保证不落库。 */
    public static class FetchException extends RuntimeException {
        public FetchException(String message) {
            super(message);
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 直链常有跳转（CDN、短链），跟随重定向是必要的；但只跟随到同协议的目标，
            // NEVER 会让一大批正常链接失败，ALWAYS 则允许 https → http 降级。
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** HEAD 探测结果。任一字段为 null 表示对方没给这个头。 */
    public record Head(String etag, String lastModified) {}

    /**
     * 变更检测的第一级：只要头，不要内容。
     *
     * <p>**失败不抛异常而是返回 {@link Optional#empty()}**：很多服务器压根不支持 HEAD，或者
     * 返回 405。那不是错误，只是这一级用不上——调用方应该退到第二级（下载后比内容哈希），
     * 而不是把整次同步判成失败。
     */
    public Optional<Head> head(String sourceUri) {
        try {
            URI uri = parse(sourceUri);
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            String etag = response.headers().firstValue("etag").orElse(null);
            String lastModified = response.headers().firstValue("last-modified").orElse(null);
            // 两个头都没有时这一级毫无信息量，直接当作不可用
            return etag == null && lastModified == null
                    ? Optional.empty() : Optional.of(new Head(etag, lastModified));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    /**
     * @param maxBytes 大小上限，与本地上传保持一致。**边下边计数，超限立刻中止**——
     *                 等下完再看大小，等于允许对方用一个无限流把磁盘写满
     */
    public Fetched fetch(String sourceUri, long maxBytes) {
        URI uri = parse(sourceUri);
        Path temp;
        try {
            temp = Files.createTempFile("xrag-fetch-", ".bin");
        } catch (IOException exception) {
            throw new FetchException("创建临时文件失败。", exception);
        }

        try {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new FetchException("远程地址返回 %d，无法抓取。".formatted(response.statusCode()));
            }
            long size = copyLimited(response.body(), temp, maxBytes);
            return new Fetched(temp, size,
                    response.headers().firstValue("content-type").orElse(null),
                    response.headers().firstValue("etag").orElse(null),
                    response.headers().firstValue("last-modified").orElse(null));
        } catch (FetchException failure) {
            deleteQuietly(temp);
            throw failure;
        } catch (InterruptedException interrupted) {
            deleteQuietly(temp);
            Thread.currentThread().interrupt();
            throw new FetchException("抓取被中断。", interrupted);
        } catch (Exception exception) {
            deleteQuietly(temp);
            throw new FetchException("无法访问该地址：" + exception.getMessage(), exception);
        }
    }

    /** 只校验协议。**能不能提取出文本要等抓完让 Tika 说了算**，Content-Type 是会撒谎的。 */
    private URI parse(String sourceUri) {
        if (sourceUri == null || sourceUri.isBlank()) {
            throw new FetchException("来源地址不能为空。");
        }
        URI uri;
        try {
            uri = new URI(sourceUri.trim());
        } catch (URISyntaxException invalid) {
            throw new FetchException("来源地址格式不正确。");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new FetchException("来源地址必须以 http:// 或 https:// 开头。");
        }
        if (uri.getHost() == null) {
            throw new FetchException("来源地址缺少主机名。");
        }
        return uri;
    }

    private long copyLimited(InputStream source, Path target, long maxBytes) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        // Files.newOutputStream 默认就是 CREATE + TRUNCATE_EXISTING + WRITE
        try (InputStream in = source; OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new FetchException(
                            "远程文件超过 %d MB 上限。".formatted(maxBytes / 1024 / 1024));
                }
                out.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new FetchException("远程文件内容为空。");
        }
        return total;
    }

    private void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // 业务已经在抛别的异常了，为一个残留临时文件再抛一个会把真正的原因盖掉
        }
    }

}
