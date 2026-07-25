package com.app.knowledge.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 分块内容的 SHA-256（十六进制小写）。
 *
 * <p>抽成公共工具而不是让两处各写一遍：自动分块（{@link TextChunker}）与手工编辑分块
 * （{@code ChunkService}）写进 {@code content_hash} 的值**必须由同一段代码算出**。两边
 * 算法一旦不一致，定时同步的内容比对就会把没变的内容判成变了，而且症状要等到 Phase 1
 * 之后才会显现。
 */
public final class ContentHash {

    private ContentHash() {}

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 未提供 SHA-256", impossible);
        }
    }

    /**
     * 文件内容的哈希，用于定时同步的第二级变更检测（HTTP 头不可信时比对内容本身）。
     *
     * <p>**分块读而不是一次读进内存**：这里的输入是刚下载的源文件，最大 50MB，整个读进堆
     * 正是学习笔记 03-03 记录的那类内存放大。
     */
    public static String sha256OfFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("计算文件哈希失败：" + file, failure);
        }
    }
}
