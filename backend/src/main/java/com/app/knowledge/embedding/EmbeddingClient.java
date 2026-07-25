package com.app.knowledge.embedding;

import java.util.List;

/**
 * 把文本转成向量。接口只有两个方法，是刻意的最小面。
 *
 * <p>Phase 2「大模型调度引擎」会引入第二个实现（多 Provider 路由、降级、限流），
 * 所以这个抽象的第二个使用者是已知的，不是假想出来的。
 *
 * <p>调用方注意：这是**外部 HTTP 调用**，绝不允许出现在事务内
 * （见 {@code com.app.knowledge} 的 package-info）。
 */
public interface EmbeddingClient {

    /**
     * 批量计算向量。
     *
     * @param texts 待向量化的文本，不能为 null；空列表直接返回空列表
     * @return 与 {@code texts} **严格一一对应**的向量列表，长度和顺序都相同
     * @throws EmbeddingException 未配置、请求失败、或返回结果与输入对不上时
     */
    List<float[]> embed(List<String> texts);

    /** 向量维度。供启动时与数据库向量列维度比对。 */
    int dimensions();
}
