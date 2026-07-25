package com.app.knowledge.model;

/**
 * 分块算法的输出：一段文本及其派生指标。还没有落库，因此没有 id / docId / revision，
 * 那些由 service 层在写入时补上。
 *
 * @param index       在本次分块结果中的顺序，从 0 开始
 * @param content     分块内容
 * @param charCount   字符数
 * @param tokenCount  启发式估算的 token 数，**只用于界面展示分块规模，不参与任何逻辑判断**
 *                    （PRD §2 非目标：不做精确 token 计数）
 * @param contentHash 内容的 SHA-256（十六进制小写），用于"内容没变就不重算向量"
 */
public record TextChunk(int index, String content, int charCount, int tokenCount, String contentHash) {}
