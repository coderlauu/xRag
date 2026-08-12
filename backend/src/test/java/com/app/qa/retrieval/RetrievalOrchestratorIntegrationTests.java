package com.app.qa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.knowledge.FakeEmbeddingConfig;
import com.app.knowledge.keyword.ChunkKeywordRepository;
import com.app.knowledge.search.ChunkSearchPort;
import com.app.knowledge.vector.ChunkVectorRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 3B-FAIL-01~09、3B-CFG-05 与端到端串接。
 *
 * <p><b>本类刻意不用 {@code @Transactional} 回滚</b>：检索任务跑在 {@code qaRetrievalExecutor}
 * 的其他线程上，那些线程拿的是各自的连接，看不见测试事务里未提交的种子数据——症状是所有
 * 用例都返回 NO_RESULT，看起来像检索坏了，实际是数据还没提交。种子数据因此真提交，
 * 用 {@code @AfterEach} 显式清理。这条也是并行检索给测试带来的真实约束，不是本类特有的怪癖。
 *
 * <p><b>刻意不加 {@code @TestPropertySource} 或 {@code @AutoConfigureMockMvc}</b>：本类的注解组合
 * 与 {@code ObjectStorageAuditIntegrationTests} 等三个类相同，因此共用同一个
 * {@code ApplicationContext}。每个上下文变体都带一个 10 连接的 Hikari 池，而 Postgres 的
 * {@code max_connections} 是 100——变体数量本身就是稀缺资源。实测给本类单独加一条属性覆盖
 * （哪怕是关掉入库派发器这种看起来只会更安全的改动）会让它独占一个新上下文，全量回归里
 * 入库测试的 {@code awaitStatus} 超时反而从 1 个涨到 3 个。加注解之前先数上下文。
 */
@SpringBootTest
@Import(FakeEmbeddingConfig.class)
class RetrievalOrchestratorIntegrationTests {

    @Autowired
    private RetrievalOrchestrator orchestrator;

    @Autowired
    private RetrievalResultRepository repository;

    @Autowired
    private ChunkVectorRepository vectors;

    @Autowired
    private ChunkKeywordRepository keywords;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired
    private javax.sql.DataSource dataSource;

    private UUID conversationId;
    private final List<Long> createdKbs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ProgrammableSearchFailure.reset();
        conversationId = UUID.randomUUID();
        createdKbs.clear();
        jdbc.update(
                "insert into qa_conversation (id, title, last_active_at) values (?, '检索编排', now())",
                conversationId);
    }

    /**
     * 按知识库精确制造检索失败的可编程装饰器。
     *
     * <p><b>装饰真实实现而不是替换它</b>：FAIL-01 要断言"失败之外的其余照常"，那个"其余"必须是
     * 真实检索的结果，换成假实现就等于把要验的东西一起假掉了。风格与
     * {@code FakeEmbeddingConfig}、{@code ProgrammableFakeProvider} 一致——本仓库的测试不用
     * Mockito 的 spy，可编程假实现都是手写的。
     *
     * <p>两个方法分属两个通道（向量 / 关键词），这正是能定向制造"关键词失败但向量照常"的原因。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class ProgrammableSearchFailure {

        /** 命中这些知识库的向量检索会失败。空集表示向量通道一切正常。 */
        static final Set<Long> FAILING_KB_IDS = ConcurrentHashMap.newKeySet();

        /** 关键词检索是否整体失败。 */
        static final AtomicBoolean KEYWORD_FAILS = new AtomicBoolean(false);

        /** 失败时抛超时类异常而不是通用异常——用来验归一化到 RETRIEVAL_TIMEOUT 那条分支。 */
        static final AtomicBoolean AS_TIMEOUT = new AtomicBoolean(false);

        /** 每次向量检索的作用域，按调用顺序记下。FAIL-07 用它证明成功目标没被再查一次。 */
        static final List<Set<Long>> VECTOR_CALLS = new CopyOnWriteArrayList<>();

        /**
         * 每次检索发生时是否处在活跃事务里。
         *
         * <p>工单 08 Acceptance 的最后一条要求"检索在事务外"。这个装饰器是链路上唯一能既看到
         * 真实调用时机、又能问出事务状态的地方——断言它全为 false 比读代码确认更耐改。
         */
        static final List<Boolean> IN_TRANSACTION = new CopyOnWriteArrayList<>();

        /** 异常 message 里塞进这段文本，用来验它不会泄漏到落库结构里（FAIL-06）。 */
        static final String LEAK_MARKER = "内部细节-不该出现在任何用户可见结构里";

        /** 融合/去重是否失败。FUSE-07 用它验"去重失败 = 本轮失败"，与精排失败降级形成对照。 */
        static final AtomicBoolean FUSION_FAILS = new AtomicBoolean(false);

        static void reset() {
            FAILING_KB_IDS.clear();
            KEYWORD_FAILS.set(false);
            AS_TIMEOUT.set(false);
            FUSION_FAILS.set(false);
            VECTOR_CALLS.clear();
            IN_TRANSACTION.clear();
        }

        /**
         * 可失败的融合。{@link ReciprocalRankFusion} 是具体类不是接口，所以这里继承它
         * ——与本仓库"可编程假实现手写"的风格一致，不引 Mockito。
         */
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        ReciprocalRankFusion programmableFusion(com.app.qa.config.QaRetrievalProperties properties) {
            return new ReciprocalRankFusion(properties) {
                @Override
                public List<FusedCandidate> fuse(List<ChannelResult> results) {
                    if (FUSION_FAILS.get()) {
                        throw new IllegalStateException(LEAK_MARKER);
                    }
                    return super.fuse(results);
                }
            };
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        ChunkSearchPort programmableSearch(
                @org.springframework.beans.factory.annotation.Qualifier("jdbcChunkSearch")
                        ChunkSearchPort real) {
            return new ChunkSearchPort() {
                @Override
                public List<ChunkMatch> searchByVector(Set<Long> kbIds, float[] queryVector, int limit) {
                    VECTOR_CALLS.add(Set.copyOf(kbIds));
                    IN_TRANSACTION.add(org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    if (kbIds.stream().anyMatch(FAILING_KB_IDS::contains)) {
                        throw failure();
                    }
                    return real.searchByVector(kbIds, queryVector, limit);
                }

                @Override
                public List<ChunkMatch> searchByKeyword(Set<Long> kbIds, String rawQuery, int limit) {
                    IN_TRANSACTION.add(org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    if (KEYWORD_FAILS.get()) {
                        throw failure();
                    }
                    return real.searchByKeyword(kbIds, rawQuery, limit);
                }
            };
        }

        private static RuntimeException failure() {
            return AS_TIMEOUT.get()
                    ? new org.springframework.dao.QueryTimeoutException(LEAK_MARKER)
                    : new IllegalStateException(LEAK_MARKER);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        for (Long kbId : createdKbs) {
            jdbc.update("delete from document_chunk_keyword where kb_id = ?", kbId);
            jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
            jdbc.update("delete from document_chunk where kb_id = ?", kbId);
            jdbc.update("delete from source_document where kb_id = ?", kbId);
            jdbc.update("delete from knowledge_base where id = ?", kbId);
        }
        jdbc.update(
                "delete from qa_retrieval_source where turn_id in "
                        + "(select id from qa_turn where conversation_id = ?)",
                conversationId);
        jdbc.update(
                "delete from qa_retrieval_target where turn_id in "
                        + "(select id from qa_turn where conversation_id = ?)",
                conversationId);
        jdbc.update(
                "delete from qa_turn_retrieval where turn_id in "
                        + "(select id from qa_turn where conversation_id = ?)",
                conversationId);
        jdbc.update(
                "delete from qa_retrieval_action where turn_id in "
                        + "(select id from qa_turn where conversation_id = ?)",
                conversationId);
        jdbc.update(
                "delete from qa_subquestion where turn_id in "
                        + "(select id from qa_turn where conversation_id = ?)",
                conversationId);
        jdbc.update("delete from qa_turn where conversation_id = ?", conversationId);
        jdbc.update("delete from qa_conversation where id = ?", conversationId);
    }

    @Test
    void 定向检索落库来源目标与漏斗计数() {
        var kb = seedKb("编排定向", "退货需要提供订单号与凭证照片");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货需要哪些材料");
        var action = seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.status()).isEqualTo(RetrievalStatus.RETRIEVED);
        assertThat(outcome.partial()).isFalse();
        assertThat(outcome.sources()).extracting(s -> s.candidate().chunkId()).contains(kb.chunkId());
        assertThat(outcome.funnel().channelHits()).isPositive();
        assertThat(outcome.funnel().afterDedup()).isLessThanOrEqualTo(outcome.funnel().channelHits());
        assertThat(outcome.funnel().afterRerank()).isLessThanOrEqualTo(outcome.funnel().afterDedup());

        assertThat(rowCount("qa_retrieval_source", turn)).isEqualTo(outcome.sources().size());
        assertThat(rowCount("qa_retrieval_target", turn)).isEqualTo(1);
        assertThat(targetStatus(turn, action)).isEqualTo("SUCCEEDED");
    }

    @Test
    void fail04_检索成功但零命中是NO_RESULT而不是失败() {
        var kb = seedKb("编排无结果", "完全无关的内容");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "zzzz不存在的词zzzz");
        seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        // 把该库的向量清掉，只留关键词索引，让向量通道也召不回东西。
        jdbc.update("delete from document_chunk_embedding where kb_id = ?", kb.kbId());

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.status()).isEqualTo(RetrievalStatus.NO_RESULT);
        assertThat(outcome.sources()).isEmpty();
        // 目标是成功的——查了，只是没有。与"没查成"必须区分开。
        assertThat(outcome.targets()).allMatch(RetrievalOutcome.TargetOutcome::succeeded);
    }

    @Test
    void fail02_partial由目标状态算出而不是由来源条数推断() {
        // 构造"条数少但全部成功"的反证：只有一条来源，partial 必须是 false。
        var kb = seedKb("编排单条", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.sources()).hasSizeLessThanOrEqualTo(2);
        assertThat(outcome.partial()).isFalse();
    }

    @Test
    void fail03_全部目标失败时是RETRIEVAL_FAILED且与无结果不同() {
        var kb = seedKb("编排全失败", "退货政策说明");
        var turn = seedTurn();
        // 用一个切不出命中的问题，让关键词通道零命中：这样 RETRIEVAL_FAILED 纯粹来自
        // "目标全失败"，而不是掺进了"关键词还召回了东西"这个变量。
        var subQuestion = seedSubQuestion(turn, "zzzz不存在的词zzzz");
        var action = seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        ProgrammableSearchFailure.FAILING_KB_IDS.add(kb.kbId());

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.status()).isEqualTo(RetrievalStatus.RETRIEVAL_FAILED);
        assertThat(outcome.targets()).noneMatch(RetrievalOutcome.TargetOutcome::succeeded);
        assertThat(outcome.sources()).isEmpty();
        // 与 NO_RESULT 是两个状态：一个没查成、一个查了没有。
        assertThat(outcome.status()).isNotEqualTo(RetrievalStatus.NO_RESULT);
        assertThat(targetStatus(turn, action)).isEqualTo("FAILED");
    }

    @Test
    void fail01_单个知识库失败时其余照常且partial点名该库() {
        var failing = seedKb("编排失败库", "退货需要订单号");
        var healthy = seedKb("编排正常库", "退款到账时间说明");
        var turn = seedTurn();
        // 两个子问题各定向一个库：同一子问题的多个 KB_TARGETED 会被合并成一次定向检索，
        // 那种形态下造不出"A 失败 B 成功"。
        var failingSq = seedSubQuestion(turn, "退货需要订单号", 0);
        var healthySq = seedSubQuestion(turn, "退款到账时间", 1);
        var failingAction = seedAction(turn, failingSq, "KB_TARGETED", failing.kbId());
        var healthyAction = seedAction(turn, healthySq, "KB_TARGETED", healthy.kbId());
        ProgrammableSearchFailure.FAILING_KB_IDS.add(failing.kbId());

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.partial()).isTrue();
        assertThat(outcome.status()).isEqualTo(RetrievalStatus.RETRIEVED);
        assertThat(targetStatus(turn, failingAction)).isEqualTo("FAILED");
        assertThat(targetStatus(turn, healthyAction)).isEqualTo("SUCCEEDED");
        // 点名该库：失败目标带着 kb id，界面才能说出是哪个库不完整。
        assertThat(outcome.targets())
                .filteredOn(target -> !target.succeeded())
                .extracting(RetrievalOutcome.TargetOutcome::knowledgeBaseId)
                .containsExactly(failing.kbId());
        // "其余照常"：正常库的内容仍然进了来源集合。
        assertThat(outcome.sources())
                .extracting(source -> source.candidate().chunkId())
                .contains(healthy.chunkId());
    }

    @Test
    void fail05_关键词通道失败不影响向量通道也不产生目标失败() {
        var kb = seedKb("编排关键词失败", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        var action = seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        ProgrammableSearchFailure.KEYWORD_FAILS.set(true);

        var outcome = orchestrator.retrieve(turn);

        // 向量通道照常召回，本轮不算部分失败——通道构成不对用户暴露（架构 §19.6）。
        assertThat(outcome.status()).isEqualTo(RetrievalStatus.RETRIEVED);
        assertThat(outcome.partial()).isFalse();
        assertThat(outcome.targets()).allMatch(RetrievalOutcome.TargetOutcome::succeeded);
        assertThat(targetStatus(turn, action)).isEqualTo("SUCCEEDED");
        assertThat(outcome.sources())
                .extracting(source -> source.candidate().chunkId())
                .contains(kb.chunkId());
    }

    @Test
    void fail06_失败原因是归一化闭集且异常message不进落库结构() {
        var kb = seedKb("编排归一化", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        ProgrammableSearchFailure.FAILING_KB_IDS.add(kb.kbId());

        orchestrator.retrieve(turn);

        var codes = jdbc.queryForList(
                "select failure_code from qa_retrieval_target where turn_id = ? and status = 'FAILED'",
                String.class,
                turn);
        assertThat(codes).isNotEmpty();
        // 闭集：每个落库的原因都必须是枚举成员，不能是异常类名或 message 的残渣。
        assertThat(codes).allSatisfy(code -> assertThat(RetrievalFailureCode.valueOf(code)).isNotNull());
        assertThat(codes).doesNotContain(ProgrammableSearchFailure.LEAK_MARKER);

        // 超时类异常走另一条归一化分支，同样不带 message。
        ProgrammableSearchFailure.AS_TIMEOUT.set(true);
        var timeoutTurn = seedTurn();
        var timeoutSq = seedSubQuestion(timeoutTurn, "退货政策");
        seedAction(timeoutTurn, timeoutSq, "KB_TARGETED", kb.kbId());

        var timeoutOutcome = orchestrator.retrieve(timeoutTurn);

        assertThat(timeoutOutcome.targets())
                .extracting(RetrievalOutcome.TargetOutcome::failureCode)
                .containsOnly(RetrievalFailureCode.RETRIEVAL_TIMEOUT);
    }

    @Test
    void fail07_重试只重跑失败目标且已成功的来源不被删掉() {
        var failing = seedKb("编排重试失败库", "退货需要订单号");
        var healthy = seedKb("编排重试正常库", "退款到账时间说明");
        var turn = seedTurn();
        var failingSq = seedSubQuestion(turn, "退货需要订单号", 0);
        var healthySq = seedSubQuestion(turn, "退款到账时间", 1);
        var failingAction = seedAction(turn, failingSq, "KB_TARGETED", failing.kbId());
        var healthyAction = seedAction(turn, healthySq, "KB_TARGETED", healthy.kbId());
        ProgrammableSearchFailure.FAILING_KB_IDS.add(failing.kbId());

        orchestrator.retrieve(turn);
        assertThat(targetStatus(turn, failingAction)).isEqualTo("FAILED");
        var healthyChunkBefore = jdbc.queryForList(
                "select chunk_id from qa_retrieval_source where turn_id = ? and action_id = ?",
                Long.class,
                turn,
                healthyAction);
        assertThat(healthyChunkBefore).contains(healthy.chunkId());

        // 故障恢复，重试。
        ProgrammableSearchFailure.FAILING_KB_IDS.clear();
        ProgrammableSearchFailure.VECTOR_CALLS.clear();
        orchestrator.retryFailed(turn);

        // 只重跑失败目标：向量检索只被那个失败库的作用域触发过，正常库没有被再查一次。
        assertThat(ProgrammableSearchFailure.VECTOR_CALLS).isNotEmpty();
        assertThat(ProgrammableSearchFailure.VECTOR_CALLS)
                .allSatisfy(scope -> assertThat(scope).doesNotContain(healthy.kbId()));

        // 合并而不是替换：上一次成功目标的来源与目标行必须还在（架构 §19.7）。
        assertThat(targetStatus(turn, healthyAction)).isEqualTo("SUCCEEDED");
        assertThat(targetStatus(turn, failingAction)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForList(
                        "select chunk_id from qa_retrieval_source where turn_id = ? and action_id = ?",
                        Long.class,
                        turn,
                        healthyAction))
                .as("按 turn_id 清台会把这条已成功的来源一起删掉")
                .contains(healthy.chunkId());
        assertThat(rowCount("qa_retrieval_target", turn)).isEqualTo(2);
    }

    @Test
    void 重试再次失败时不把整轮判成全部失败() {
        var failing = seedKb("编排重试再失败", "退货需要订单号");
        var healthy = seedKb("编排重试仍正常", "退款到账时间说明");
        var turn = seedTurn();
        var failingSq = seedSubQuestion(turn, "退货需要订单号", 0);
        var healthySq = seedSubQuestion(turn, "退款到账时间", 1);
        var failingAction = seedAction(turn, failingSq, "KB_TARGETED", failing.kbId());
        var healthyAction = seedAction(turn, healthySq, "KB_TARGETED", healthy.kbId());
        ProgrammableSearchFailure.FAILING_KB_IDS.add(failing.kbId());

        orchestrator.retrieve(turn);
        // 故障没恢复就重试：本批全失败，但整轮仍有一个成功目标。
        var outcome = orchestrator.retryFailed(turn);

        assertThat(outcome.status())
                .as("本批全失败不等于整轮全失败——status 要按全量目标重算")
                .isEqualTo(RetrievalStatus.RETRIEVED);
        assertThat(outcome.partial()).isTrue();
        assertThat(targetStatus(turn, healthyAction)).isEqualTo("SUCCEEDED");
        assertThat(targetStatus(turn, failingAction)).isEqualTo("FAILED");
    }

    @Test
    void 同一子问题同时有定向与全局动作时每条动作只记一个目标() {
        // 架构 §9.4：单个 KB 候选且分数 <0.8 时，3A **同时**产出 KB_TARGETED 与
        // GLOBAL_FALLBACK（低置信度补充）。ADR 0008 修正后两个向量通道都会激活，于是
        // 一个子问题下有两个通道任务，而每个任务都持有该子问题的全部动作——按动作平铺
        // 记目标就会把同一条动作记两次，撞 uq_qa_target。
        //
        // 这个组合只有真实链路才产生，所以工单 08 阶段没被任何用例覆盖；把检索接进
        // TurnProcessor 之后它立刻以 DuplicateKeyException 暴露出来。
        var kb = seedKb("编排双动作", "退款到账时间说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退款多久到账");
        var targeted = seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        var fallback = seedAction(turn, subQuestion, "GLOBAL_FALLBACK", null);

        var outcome = orchestrator.retrieve(turn);

        // 一条动作一个目标，不多不少。
        assertThat(rowCount("qa_retrieval_target", turn)).isEqualTo(2);
        assertThat(outcome.targets()).hasSize(2);
        assertThat(targetStatus(turn, targeted)).isEqualTo("SUCCEEDED");
        assertThat(targetStatus(turn, fallback)).isEqualTo("SUCCEEDED");
    }

    @Test
    void 向量全失败但关键词有召回时是RETRIEVED且结果不完整() {
        // 裁决（`2026-08-11`，契约 §9.1）。这是无密钥部署的常态：目标只由向量通道产生，
        // 无密钥时向量必然全失败，而关键词通道不产生目标却真的召回了。
        var kb = seedKb("编排关键词兜底", "退货政策说明与凭证要求");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        var action = seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        ProgrammableSearchFailure.FAILING_KB_IDS.add(kb.kbId());

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.sources())
                .as("关键词通道不受向量失败影响，它该召回")
                .isNotEmpty();
        assertThat(outcome.status())
                .as("有来源就是 RETRIEVED——用户手上有结果，说『没查成』是错的")
                .isEqualTo(RetrievalStatus.RETRIEVED);
        assertThat(outcome.partial())
                .as("但确实少了一块，界面要挂『结果不完整』并给重试")
                .isTrue();
        assertThat(targetStatus(turn, action)).isEqualTo("FAILED");
    }

    @Test
    void 目标全失败且没有来源时才是RETRIEVAL_FAILED() {
        // 裁决的另一半：没有来源时仍然要分辨"没查成"（可重试）与"查了但没有"（该改问法），
        // 这正是 PRD §12.3 的两件事。用切不出命中的问题让关键词也零命中。
        var kb = seedKb("编排全失败无来源", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "zzzz不存在的词zzzz");
        seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        ProgrammableSearchFailure.FAILING_KB_IDS.add(kb.kbId());

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.sources()).isEmpty();
        assertThat(outcome.status()).isEqualTo(RetrievalStatus.RETRIEVAL_FAILED);
        assertThat(outcome.partial())
                .as("什么都没有不是『不完整』，那是『没查成』")
                .isFalse();
    }

    @Test
    void cfg02_连接池按显式配置生效而不是落回默认值() {
        // 池大小是并行检索的硬约束（max-parallelism 必须严格小于它，见 CFG-01），所以"配置到底
        // 有没有生效"本身要被验证——测试期把它压到 6 正是为了给上下文变体留余量，如果它悄悄
        // 落回默认 10，CFG-01 的边界校验就是按错误的数字算的。
        String configured = environment.getProperty("spring.datasource.hikari.maximum-pool-size");
        assertThat(configured)
                .as("测试期应显式配置池大小（见 backend/pom.xml 的 surefire 属性）")
                .isNotNull();

        var hikari = (com.zaxxer.hikari.HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize())
                .as("运行时池大小必须等于显式配置，不能落回 Hikari 默认的 10")
                .isEqualTo(Integer.parseInt(configured));
    }

    @Test
    void ch06_检索只读动作行不依赖意图分数也不回读当前树() {
        // 3B 只消费 3A 写死的动作行（架构 §15）。这里让动作指向一个**不存在的意图节点**、
        // 且整轮没有任何 qa_intent_match 记录——如果编排真去回读意图树或分数，这一轮必然报错
        // 或召不回；它照常工作，就是"不回读"的证据。
        var kb = seedKb("编排只读动作", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        var action = seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());

        // 前提确认：这一轮确实没有意图评分记录，否则下面的断言证明不了什么。
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_intent_match where turn_id = ?", Integer.class, turn))
                .isZero();
        // 动作里的 intent_node_id 指向不存在的节点（seedAction 用的是随机 UUID）。
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_retrieval_action where turn_id = ? and intent_node_id is not null",
                        Integer.class,
                        turn))
                .isEqualTo(1);

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.status()).isEqualTo(RetrievalStatus.RETRIEVED);
        assertThat(outcome.sources()).isNotEmpty();
        assertThat(targetStatus(turn, action)).isEqualTo("SUCCEEDED");
    }

    @Test
    void fuse07_去重失败让本轮失败而不是像精排那样降级() {
        // 两种容错**刻意不混用**：精排失败降级为未精排、本轮照常返回（rank04 验的那条）；
        // 而融合/去重是结果正确性的一部分，它失败时给出一份"少了一些、但看起来正常"的来源
        // 比整轮失败更危险——用户没有任何线索知道结果被悄悄削减了。
        var kb = seedKb("编排融合失败", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        ProgrammableSearchFailure.FUSION_FAILS.set(true);

        assertThatThrownBy(() -> orchestrator.retrieve(turn))
                .as("融合失败必须让本轮失败，不能降级成一份被悄悄削减的结果")
                .isInstanceOf(RuntimeException.class);

        // 没有留下半套记录：失败的那一轮不该在库里留下"看起来成功"的痕迹。
        assertThat(rowCount("qa_turn_retrieval", turn)).isZero();
        assertThat(rowCount("qa_retrieval_source", turn)).isZero();
    }

    @Test
    void 检索发生在事务外() {
        var kb = seedKb("编排事务边界", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());

        orchestrator.retrieve(turn);

        // 事务只允许覆盖最后的落库（架构 §19.1）。把检索圈进事务里的代价是一条只读连接
        // 被整个检索时长占住，并行度一上去就会连接池耗尽——而症状是"检索偶发超时"，
        // 看起来完全像知识库慢，不像事务边界画错了。
        assertThat(ProgrammableSearchFailure.IN_TRANSACTION)
                .isNotEmpty()
                .as("检索必须在事务外")
                .containsOnly(false);
    }

    @Test
    void cfg05_任务数超过并行度上限时全部完成且无连接获取失败() {
        // 子问题上限是 3（ck_qa_subquestion_ordinal: 0..2），所以并发压力靠通道数堆：
        // 3 个子问题各定向 2 个库 → 3 个定向任务 + 3 个始终激活的关键词任务 = 6 个任务，
        // 稳定超过 maxParallelism 默认的 4，逼出排队。每个定向任务覆盖 2 个目标。
        var turn = seedTurn();
        var actions = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) {
            var first = seedKb("编排并发" + i + "a", "退货政策与退款到账说明 " + i);
            var second = seedKb("编排并发" + i + "b", "退货凭证与发票说明 " + i);
            var subQuestion = seedSubQuestion(turn, "退货政策 " + i, i);
            actions.add(seedAction(turn, subQuestion, "KB_TARGETED", first.kbId()));
            actions.add(seedAction(turn, subQuestion, "KB_TARGETED", second.kbId()));
        }

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.targets()).hasSize(6);
        assertThat(outcome.targets())
                .as("连接获取超时会被归一化成 KNOWLEDGE_BASE_UNAVAILABLE")
                .allMatch(RetrievalOutcome.TargetOutcome::succeeded);
        assertThat(outcome.partial()).isFalse();
        for (UUID action : actions) {
            assertThat(targetStatus(turn, action)).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void fail09_没有失败目标时重试被拒绝() {
        var kb = seedKb("编排重试", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        orchestrator.retrieve(turn);

        assertThatThrownBy(() -> orchestrator.retryFailed(turn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("NOTHING_TO_RETRY");
    }

    @Test
    void fail08_重试不改变理解结果字段() {
        var kb = seedKb("编排重试不改理解", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        var action = seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());
        orchestrator.retrieve(turn);

        // 人为把目标标为失败，模拟上一次部分失败。
        jdbc.update(
                "update qa_retrieval_target set status = 'FAILED', failure_code = 'RETRIEVAL_TIMEOUT' "
                        + "where turn_id = ? and action_id = ?",
                turn,
                action);
        var before = understandingSnapshot(turn, subQuestion);

        orchestrator.retryFailed(turn);

        // 重试后子问题、动作、turn 状态逐字段不变——用户确认过的方向不能被重问。
        assertThat(understandingSnapshot(turn, subQuestion)).isEqualTo(before);
        assertThat(targetStatus(turn, action)).isEqualTo("SUCCEEDED");
    }

    @Test
    void 多子问题的来源在同一轮内按chunk去重() {
        var kb = seedKb("编排多子问题", "退货政策与退款到账说明");
        var turn = seedTurn();
        var first = seedSubQuestion(turn, "退货政策", 0);
        var second = seedSubQuestion(turn, "退款到账", 1);
        seedAction(turn, first, "KB_TARGETED", kb.kbId());
        seedAction(turn, second, "KB_TARGETED", kb.kbId());

        var outcome = orchestrator.retrieve(turn);

        var chunkIds = outcome.sources().stream().map(s -> s.candidate().chunkId()).toList();
        assertThat(chunkIds).doesNotHaveDuplicates();
        // 数据库的 uq_qa_source_chunk 是第二道闸；这里断言落库真的成功了。
        assertThat(rowCount("qa_retrieval_source", turn)).isEqualTo(chunkIds.size());
    }

    @Test
    void 没有可执行动作时既不失败也不算查过() {
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "闲聊");
        seedAction(turn, subQuestion, "NO_MATCH", null);

        var outcome = orchestrator.retrieve(turn);

        assertThat(outcome.status()).isEqualTo(RetrievalStatus.NO_RESULT);
        assertThat(outcome.targets()).isEmpty();
        assertThat(outcome.funnel().channelHits()).isZero();
    }

    @Test
    void 重复执行同一轮不留下上一次的半套数据() {
        var kb = seedKb("编排幂等", "退货政策说明");
        var turn = seedTurn();
        var subQuestion = seedSubQuestion(turn, "退货政策");
        seedAction(turn, subQuestion, "KB_TARGETED", kb.kbId());

        var first = orchestrator.retrieve(turn);
        var second = orchestrator.retrieve(turn);

        assertThat(rowCount("qa_retrieval_source", turn)).isEqualTo(second.sources().size());
        assertThat(second.sources()).hasSameSizeAs(first.sources());
        assertThat(rowCount("qa_turn_retrieval", turn)).isEqualTo(1);
    }

    // ——— helpers ———

    private record Kb(long kbId, long docId, long chunkId) {}

    private Kb seedKb(String name, String content) {
        String alias = name + "-" + UUID.randomUUID();
        long kbId = jdbc.queryForObject(
                """
                insert into knowledge_base (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'test', 1024) returning id
                """,
                Long.class,
                alias,
                alias);
        long docId = jdbc.queryForObject(
                """
                insert into source_document (kb_id, name, source_type, storage_object_id)
                values (?, 'doc.txt', 'FILE', ?) returning id
                """,
                Long.class,
                kbId,
                UUID.randomUUID().toString());
        long chunkId = jdbc.queryForObject(
                """
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                values (?, ?, 1, 0, ?, ?, 1,
                        '0000000000000000000000000000000000000000000000000000000000000000')
                returning id
                """,
                Long.class,
                kbId,
                docId,
                content,
                content.length());
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        vectors.insertAll(kbId, docId, List.of(chunkId), List.of(vector));
        assertThat(keywords.countByDocId(docId)).isEqualTo(1);
        createdKbs.add(kbId);
        return new Kb(kbId, docId, chunkId);
    }

    private UUID seedTurn() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, completed_at)
                values (?, ?, ?, ?, 'ACTIONS_READY', now())
                """,
                id,
                conversationId,
                UUID.randomUUID(),
                UUID.randomUUID());
        return id;
    }

    private UUID seedSubQuestion(UUID turnId, String text) {
        return seedSubQuestion(turnId, text, 0);
    }

    private UUID seedSubQuestion(UUID turnId, String text, int ordinal) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into qa_subquestion (id, turn_id, ordinal, text, source) values (?, ?, ?, ?, 'MODEL')",
                id,
                turnId,
                ordinal,
                text);
        return id;
    }

    private UUID seedAction(UUID turnId, UUID subQuestionId, String type, Long kbId) {
        UUID id = UUID.randomUUID();
        UUID nodeId = "KB_TARGETED".equals(type) ? UUID.randomUUID() : null;
        jdbc.update(
                """
                insert into qa_retrieval_action
                    (id, turn_id, subquestion_id, ordinal, action_type,
                     intent_node_id, knowledge_base_id, reason_code)
                values (?, ?, ?,
                        (select coalesce(max(ordinal), 0) + 1 from qa_retrieval_action where subquestion_id = ?),
                        ?, ?, ?, 'TEST')
                """,
                id,
                turnId,
                subQuestionId,
                subQuestionId,
                type,
                nodeId,
                kbId);
        return id;
    }

    private int rowCount(String table, UUID turnId) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where turn_id = ?", Integer.class, turnId);
    }

    private String targetStatus(UUID turnId, UUID actionId) {
        return jdbc.queryForObject(
                "select status from qa_retrieval_target where turn_id = ? and action_id = ?",
                String.class,
                turnId,
                actionId);
    }

    private List<String> understandingSnapshot(UUID turnId, UUID subQuestionId) {
        var snapshot = new ArrayList<String>();
        snapshot.add(jdbc.queryForObject("select status from qa_turn where id = ?", String.class, turnId));
        snapshot.add(jdbc.queryForObject(
                "select text from qa_subquestion where id = ?", String.class, subQuestionId));
        snapshot.addAll(jdbc.queryForList(
                "select action_type from qa_retrieval_action where subquestion_id = ? order by ordinal",
                String.class,
                subQuestionId));
        return snapshot;
    }
}
