package com.app.knowledge.ingestion;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 {@code @Scheduled}。放在本模块内而不是主 {@code Application} 上——调度是知识库
 * 入库/定时同步的需要，不是全局能力，将来若要关掉只动这一个文件。
 */
@Configuration
@EnableScheduling
public class IngestionSchedulingConfig {}
