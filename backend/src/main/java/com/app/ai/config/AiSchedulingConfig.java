package com.app.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 {@code com.app.ai} 自己的 {@code @Scheduled}（当前只有诊断保留清理）。
 *
 * <p>知识库模块的 {@code IngestionSchedulingConfig} 已经开过一次，重复开启是幂等的
 * （Spring 按固定 bean 名注册同一份调度基础设施）。这里仍然单独声明，是为了不让本模块的
 * 保留任务依赖另一个模块的配置存在——那个文件的注释写明了"将来若要关掉只动这一个文件"，
 * 真关掉时保留清理不该跟着静默停摆。
 */
@Configuration
@EnableScheduling
public class AiSchedulingConfig {}
