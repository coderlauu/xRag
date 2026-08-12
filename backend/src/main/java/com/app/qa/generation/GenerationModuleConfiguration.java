package com.app.qa.generation;

import com.app.ai.model.StreamingChatClient;
import com.app.qa.config.QaGenerationProperties;
import com.app.qa.prompt.GenerationPromptAssembler;
import com.app.qa.prompt.PromptWindowBudget;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class GenerationModuleConfiguration {

    @Bean
    Clock qaGenerationClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService qaGenerationDeadlineScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "qa-generation-deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    GenerationCommandFactory generationCommandFactory(JdbcTemplate jdbc) {
        return new GenerationCommandFactory(jdbc);
    }

    @Bean
    GenerationReadService generationReadService(JdbcTemplate jdbc) {
        return new GenerationReadService(jdbc);
    }

    @Bean
    GenerationTaskRecovery generationTaskRecovery(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new GenerationTaskRecovery(jdbc, transactionManager);
    }

    @Bean(destroyMethod = "close")
    GenerationTasks generationTasks(
            StreamingChatClient streamingChat,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            QaGenerationProperties properties,
            Clock qaGenerationClock,
            ScheduledExecutorService qaGenerationDeadlineScheduler,
            @Qualifier("qaGenerationExecutor") ThreadPoolTaskExecutor generationExecutor) {
        var taskStore = new JdbcGenerationTaskStore(jdbc, transactionManager);
        var settlements = new JdbcTaskSettlementRepository(
                jdbc, transactionManager, new JdbcAssistantAnswerStore(jdbc));
        var admissions = new FairGenerationAdmissionQueue(
                properties.getMaxConcurrency(),
                properties.getQueueCapacity(),
                properties.getQueueWait(),
                qaGenerationClock,
                FairGenerationAdmissionQueue.DeadlineScheduler.using(
                        qaGenerationDeadlineScheduler, qaGenerationClock));
        return new DefaultGenerationTasks(
                streamingChat,
                new GenerationPromptAssembler(PromptWindowBudget.fixedInputBytes(65_536)),
                taskStore,
                settlements,
                admissions,
                generationExecutor,
                qaGenerationDeadlineScheduler,
                properties.getFirstByteTimeout(),
                properties.getModelDeadline());
    }

    @Bean
    GenerationSseObservers generationSseObservers(
            QaGenerationProperties properties,
            Clock qaGenerationClock,
            ScheduledExecutorService qaGenerationDeadlineScheduler,
            @Qualifier("qaGenerationPushExecutor") ThreadPoolTaskExecutor pushExecutor) {
        return emitter -> new SseGenerationObserver(
                emitter,
                pushExecutor,
                qaGenerationDeadlineScheduler,
                qaGenerationClock,
                properties.getPushQueueCapacity());
    }
}
