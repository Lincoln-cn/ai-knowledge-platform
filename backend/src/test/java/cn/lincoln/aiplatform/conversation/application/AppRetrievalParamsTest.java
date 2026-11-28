package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.EventKind;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamEvent;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamStatus;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.model.ModelEgressPort;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@code #185}：应用级检索参数必须真正进入问答链路。
 *
 * <h3>★★ 每条断言都能红在哪</h3>
 *
 * <ul>
 *   <li>阈值 / TopN：把 {@code ChatService.retrieve} 改回读平台设置（{@code policyResolver} 为 null ⇒ 阈值 0 /
 *       TopN 5）⇒ 两条读数都变成 3，断言立刻红；
 *   <li>无引用拒答：把 {@code refuses(...)} 那个分支删掉 ⇒ 模型会被调用、正文变成假模型那句答案，两条断言都红。
 * </ul>
 *
 * <p>★ 本类不碰数据库、不碰 Spring：它读的是"应用参数有没有被消费"这件事，与落库无关。
 */
class AppRetrievalParamsTest {

  private static final UUID APP_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000a1");
  private static final UUID MODEL_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000a2");
  private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000a3");
  private static final UUID MODEL_ID_2 = UUID.fromString("0a000000-0000-4000-8000-0000000000a5");
  private static final UUID KB_ID_2 = UUID.fromString("0a000000-0000-4000-8000-0000000000a4");
  private static final UUID SEGMENT_1 = UUID.fromString("0a000000-0000-4000-8000-0000000000b1");
  private static final UUID SEGMENT_2 = UUID.fromString("0a000000-0000-4000-8000-0000000000b2");
  private static final UUID SEGMENT_3 = UUID.fromString("0a000000-0000-4000-8000-0000000000b3");
  private static final UUID CONVERSATION_ID =
      UUID.fromString("0a000000-0000-4000-8000-0000000000e1");
  private static final String QUESTION = "差旅住宿标准";

  private static final ModelEgressPort UNRESTRICTED_EGRESS =
      modelId -> ClassificationLevel.CONFIDENTIAL;

  @Test
  void thresholdFromTheAppChangesTheNumberOfRefs() {
    RetrievalParams strict =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.99, 5, NoRefStrategy.FREE_ANSWER);
    RetrievalParams loose =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.01, 5, NoRefStrategy.FREE_ANSWER);

    Run strictRun =
        run(strict, List.of(hit(SEGMENT_1, 0.90), hit(SEGMENT_2, 0.50), hit(SEGMENT_3, 0.20)));
    Run looseRun =
        run(loose, List.of(hit(SEGMENT_1, 0.90), hit(SEGMENT_2, 0.50), hit(SEGMENT_3, 0.20)));

    assertThat(strictRun.refs()).as("阈值 0.99 ⇒ 三条命中（0.90 / 0.50 / 0.20）全部低于阈值 ⇒ 零引用").isEmpty();
    assertThat(looseRun.refs())
        .as("阈值 0.01 ⇒ 三条都进上下文（★ 若 ChatService 仍读平台设置，这里会变成 0 / 3 与 0 / 3 一样）")
        .hasSize(3);
  }

  @Test
  void topNFromTheAppCapsTheNumberOfRefs() {
    RetrievalParams topTwo =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.01, 2, NoRefStrategy.FREE_ANSWER);

    Run run =
        run(topTwo, List.of(hit(SEGMENT_1, 0.90), hit(SEGMENT_2, 0.50), hit(SEGMENT_3, 0.20)));

    assertThat(run.refs())
        .as("应用 TopN=2 ⇒ 只留 2 条；改回平台 TopN（5）这里会变成 3")
        .hasSize(2)
        .extracting(SourceRef::segmentId)
        .containsExactly(SEGMENT_1.toString(), SEGMENT_2.toString());
  }

  @Test
  void refuseStrategyDoesNotCallTheModelWhenThereAreNoRefs() {
    RetrievalParams refuse =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.99, 5, NoRefStrategy.REFUSE);

    Run run = run(refuse, List.of(hit(SEGMENT_1, 0.90)));

    assertThat(run.refs()).as("前置：阈值把唯一命中过滤掉 ⇒ 零引用").isEmpty();
    assertThat(run.modelCalls.get()).as("★★ REFUSE：零引用时**不得**调用模型 —— 删掉那个分支它就会变成 1").isZero();
    assertThat(run.sink.deltas())
        .as("零引用拒答：正文必须是固定拒答文案，而不是假模型那句自由回答")
        .containsExactly(ChatService.NO_REF_REFUSAL);
    assertThat(run.sink.done().status())
        .as("拒答仍是一次正常收尾（GENERATED），不是失败")
        .isEqualTo(StreamStatus.GENERATED);
  }

  @Test
  void freeAnswerStrategyStillCallsTheModelWhenThereAreNoRefs() {
    RetrievalParams free =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.99, 5, NoRefStrategy.FREE_ANSWER);

    Run run = run(free, List.of(hit(SEGMENT_1, 0.90)));

    assertThat(run.refs()).isEmpty();
    assertThat(run.modelCalls.get())
        .as("★ 同一读数下的对照：FREE_ANSWER 必须调用模型 —— 否则上面的'拒答没调模型'证明不了是策略生效")
        .isEqualTo(1);
    assertThat(run.sink.deltas()).containsExactly("假模型答案");
  }

  // ──────────────── #185：检索模式 VECTOR / FULLTEXT / HYBRID ────────────────

  @Test
  void vectorModeEmbedsQuestionAndUsesVectorSearch() {
    RetrievalParams vector =
        new RetrievalParams(RetrievalMode.VECTOR, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(
            vector,
            new FixedSearchPort(List.of(), List.of(hit(SEGMENT_1, 0.90))),
            List.of(0.1f, 0.2f));

    assertThat(run.embeddingCalls.get())
        .as("★ VECTOR：问题必须真的被向量化一次 —— 不调 embed 它就变成 0")
        .isEqualTo(1);
    assertThat(run.search.fullTextCalls.get()).as("★ VECTOR：不得走全文那一路").isZero();
    assertThat(run.search.lastVectorModelId)
        .as("★ 向量查询必须带上发起检索的 embedding 模型 id（#192：否则别的模型写的同维度旧向量会被混进来）")
        .isEqualTo(MODEL_ID);
    assertThat(run.refs())
        .as("向量一路返回 SEGMENT_1 ⇒ refs 必须含它")
        .extracting(SourceRef::segmentId)
        .contains(SEGMENT_1.toString());
  }

  @Test
  void fullTextModeDoesNotEmbed() {
    RetrievalParams fullText =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(fullText, new FixedSearchPort(List.of(hit(SEGMENT_1, 0.90)), List.of()), List.of());

    assertThat(run.embeddingCalls.get())
        .as("★ FULLTEXT：不得向量化 —— 一旦它变成 1，说明 FULLTEXT 被误改走向量")
        .isZero();
    assertThat(run.search.vectorSearchCalls.get()).as("★ FULLTEXT：不得调用向量检索").isZero();
    assertThat(run.refs()).extracting(SourceRef::segmentId).contains(SEGMENT_1.toString());
  }

  @Test
  void hybridModeUsesBothRoutesAndFuses() {
    RetrievalParams hybrid =
        new RetrievalParams(RetrievalMode.HYBRID, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(
            hybrid,
            new FixedSearchPort(List.of(hit(SEGMENT_1, 0.20)), List.of(hit(SEGMENT_2, 0.90))),
            List.of(0.1f, 0.2f));

    assertThat(run.search.fullTextCalls.get()).as("★ HYBRID：全文那一路必须跑").isEqualTo(1);
    assertThat(run.embeddingCalls.get()).as("★ HYBRID：向量那一路也必须跑").isEqualTo(1);
    assertThat(run.search.vectorSearchCalls.get()).isEqualTo(1);
    assertThat(run.refs())
        .as("★ HYBRID：两路都参与 ⇒ refs 必须同时含两路各自的分段（融合后去重但两路都留）")
        .extracting(SourceRef::segmentId)
        .contains(SEGMENT_1.toString(), SEGMENT_2.toString());
  }

  @Test
  void hybridModeFallsBackToFullTextWhenVectorFails() {
    RetrievalParams hybrid =
        new RetrievalParams(RetrievalMode.HYBRID, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(
            hybrid,
            new FixedSearchPort(List.of(hit(SEGMENT_1, 0.80)), List.of(hit(SEGMENT_2, 0.90))),
            List.of(0.1f, 0.2f),
            true,
            MODEL_ID);

    assertThat(run.search.fullTextCalls.get()).as("★ 全文那一路仍然要跑").isEqualTo(1);
    assertThat(run.embeddingCalls.get()).as("向量那一路确实尝试过").isEqualTo(1);
    assertThat(run.search.vectorSearchCalls.get()).as("向量化失败就不再查向量").isZero();
    assertThat(run.refs())
        .as("★★ 向量失败时只保留全文那一路；不许把整轮打成零引用")
        .extracting(SourceRef::segmentId)
        .containsExactly(SEGMENT_1.toString());
  }

  @Test
  void vectorModeKeepsHealthyModelGroupsWhenOneGroupFails() {
    RetrievalParams vector =
        new RetrievalParams(RetrievalMode.VECTOR, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    FixedSearchPort search =
        new FixedSearchPort(
            List.of(),
            List.of(),
            false,
            List.of(KB_ID, KB_ID_2),
            Map.of(KB_ID, MODEL_ID, KB_ID_2, MODEL_ID_2),
            Map.of(KB_ID, List.of(hit(SEGMENT_1, 0.90)), KB_ID_2, List.of(hit(SEGMENT_2, 0.90))));
    Run run = run(vector, search, List.of(0.1f, 0.2f), true, MODEL_ID_2);

    assertThat(run.search.vectorSearchCalls.get()).as("★ 坏掉一个向量模型，另一个模型组仍要检索").isEqualTo(1);
    assertThat(run.refs())
        .as("★★ 分组隔离：坏组的失败不许让好组也零引用")
        .extracting(SourceRef::segmentId)
        .containsExactly(SEGMENT_1.toString());
  }

  @Test
  void vectorModeDoesNotQueryConversationScopeWithTheKnowledgeBaseVector() {
    RetrievalParams vector =
        new RetrievalParams(RetrievalMode.VECTOR, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    FixedSearchPort search = new FixedSearchPort(List.of(), List.of(hit(SEGMENT_1, 0.90)), true);

    Run run = run(vector, search, List.of(0.1f, 0.2f));

    assertThat(run.refs()).as("知识库那一路仍然要返回引用").isNotEmpty();
    assertThat(search.lastVectorScope).as("向量查询必须真的发生").isNotNull();
    assertThat(search.lastVectorScope.hasConversationScope())
        .as("★★ 向量查询不得带会话域 —— 会话分段没有库行，会拿错向量模型/维度表")
        .isFalse();
    assertThat(search.lastVectorScope.sharedKbIds()).as("摘会话域时不许把知识库也一起摘掉").containsExactly(KB_ID);
  }

  @Test
  void vectorModeWithoutEmbeddingPortReturnsNoRefsAndDoesNotFallBackToFullText() {
    RetrievalParams vector =
        new RetrievalParams(RetrievalMode.VECTOR, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(
            vector,
            new FixedSearchPort(List.of(hit(SEGMENT_1, 0.90)), List.of(hit(SEGMENT_1, 0.90))),
            List.of(),
            false);

    assertThat(run.refs()).as("★ 未装配 EmbeddingPort ⇒ 零引用，绝不静默退回全文").isEmpty();
    assertThat(run.search.fullTextCalls.get())
        .as("★★ 向量失败不得退回全文那一路 —— fullText 一旦非 0，就说明发生了'静默降级'")
        .isZero();
    assertThat(run.embeddingCalls.get()).isZero();
  }

  // ──────────────── 会话附件必须真的参与检索（`#187` 收口） ────────────────

  /**
   * ★★ 会话域命中不吃"准确度阈值"。
   *
   * <p>红在哪：把 `retrieve` 里 `conversationScoped` 那个例外删掉 ⇒ 0.06 分那条被当成共用库命中丢掉，引用从 1 条变 0
   * 条。这正是"附件传上去了、库里也有分段、界面却一条引用都没有"的成因之一。
   */
  @Test
  void conversationScopeHitsIgnoreTheVectorThreshold() {
    RetrievalParams strict =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.30, 5, NoRefStrategy.FREE_ANSWER);
    FixedSearchPort search =
        new FixedSearchPort(List.of(hit(SEGMENT_1, 0.06), hit(SEGMENT_2, 0.05)), List.of());
    // SEGMENT_1 = 会话附件（没有库行）；SEGMENT_2 仍是共用库里的分段
    search.conversationSegments = Set.of(SEGMENT_1);

    Run run = run(strict, search, List.of(), false);

    assertThat(run.refs())
        .as("★ 0.30 的向量阈值不该卡住会话域那条 0.06 分的 ts_rank 命中（跨量纲），共用库那条 0.05 分必须被丢掉")
        .hasSize(1);
    assertThat(run.refs().getFirst().segmentId()).isEqualTo(SEGMENT_1.toString());
    assertThat(run.refs().getFirst().kbId())
        .as("★ 会话域引用的 kbId 必须为空 —— 那是前端区分「知识库引用 / 附件引用」的判据，不许兜底")
        .isNull();
  }

  /**
   * ★★ 通用问答没开全局召回时，会话附件仍然必须参与。
   *
   * <p>红在哪：把 `retrieve` 开关早退里的 `!hasAttachments` 删掉（回到"没开召回就零引用"）⇒ 全文那一路一次都不跑， 断言从 1 条变 0
   * 条。这条同时钉住"`retrieval_enabled=false` 管的是隐式搜全部可见库，不是把用户刚传的附件也关掉"。
   */
  @Test
  void attachmentKeepsRetrievalAliveWhenTheGeneralQaSwitchIsOff() {
    FixedSearchPort search = new FixedSearchPort(List.of(hit(SEGMENT_1, 0.06)), List.of());
    search.conversationSegments = Set.of(SEGMENT_1);

    Run run = run(switchedOffPolicy(), search, true);

    assertThat(search.fullTextCalls.get()).as("★ 有附件 ⇒ 召回开关不能把检索拦在门外").isEqualTo(1);
    assertThat(run.refs()).as("★ 附件的分段必须进引用").hasSize(1);
  }

  /**
   * 反证（上一条的另一侧）：**没有附件**时，召回开关仍然要真的关掉检索。
   *
   * <p>红在哪：若把 `!hasAttachments` 写成恒真（或干脆删掉开关判断），这条会从"全文零调用"变成"跑了一次"。 ★ 没有这条，上一条的"绿"无法排除"那个开关整个失效了"。
   */
  @Test
  void withoutAttachmentsTheSwitchStillStopsRetrieval() {
    FixedSearchPort search = new FixedSearchPort(List.of(hit(SEGMENT_1, 0.06)), List.of());

    Run run = run(switchedOffPolicy(), search, false);

    assertThat(search.fullTextCalls.get()).as("没有附件 + 没开召回 ⇒ 一次全文检索都不该发生").isZero();
    assertThat(run.refs()).isEmpty();
    assertThat(run.sink.notice()).as("★ 零引用必须带上原因 —— 否则界面上「没开召回」与「知识库里确实没有」长得一模一样").contains("召回");
  }

  /**
   * ★★ 附件豁免必须**停在会话域**：不许顺带把「全部可见库」重新打开。
   *
   * <p>红在哪：真栈实测（2026-11-18，桩网关）—— 召回开关关着、只传了一个附件，引用列表里却出现了 企业会计准则 / Vue 文档 / 信息化建设工作报告。它们来自"不传库 =
   * 全部可见"这条兜底， 而用户关掉召回的理由（噪声污染）一条都没解决；更糟的是引用看起来还挺相关，比明显跑题更难发现。
   *
   * <p>⇒ 判据：这一刻构造检索范围时 {@code includeAllVisible} 必须是 {@code false}。 把 ChatService
   * 里那个四参调用改回三参，这条立刻红。
   */
  @Test
  void attachmentExemptionStopsAtTheConversationScope() {
    FixedSearchPort search = new FixedSearchPort(List.of(hit(SEGMENT_1, 0.06)), List.of());
    search.conversationSegments = Set.of(SEGMENT_1);

    Run run = run(switchedOffPolicy(), search, true);

    assertThat(run.refs()).as("有附件时检索仍然要跑（上一条的判据，防止这条被误写成「干脆关掉豁免」）").hasSize(1);
    assertThat(search.lastIncludeAllVisible)
        .as("★ 开关关着 + 有附件 ⇒ 范围只能是「显式绑定的库 + 本会话」，不许展开成全部可见库")
        .isFalse();
  }

  /**
   * ★★ 附件没进来时，零引用必须怪附件，不许怪知识库（`#358`）。
   *
   * <p>红在哪：真栈实测（2026-11-18）—— 上传 1250 字附件后问「巡检口令是什么？」，两次不同问法都只得到
   * 「检索跑完了，但知识库里没有与这个问题相关的内容」。而库里那条文档的真实状态是 {@code SEGMENT_FAILED}， {@code fail_reason} = 「该内容 501
   * 字，超出当前向量模型上限 500 字。」 —— 一句听起来完全合理、却把用户 指向错误方向的真话：他会去改问法、去翻知识库，谁也想不到要重传那个文件。
   *
   * <p>⇒ 判据：零引用且本会话附件检索不到时，notice 必须是附件那句话，且**不能**出现「知识库里没有」。
   */
  @Test
  void aBrokenAttachmentIsBlamedForTheEmptyResultNotTheLibrary() {
    String failure =
        "附件「长文档分段测试.md」没能进入检索：该内容 501 字，超出当前向量模型上限 500 字。" + " 可以删掉重传一次，或换一个格式（这份解析失败了）。";

    Run run = runWithBrokenAttachment(failure);

    assertThat(run.search.fullTextCalls.get())
        .as("附件检索不到也要真的去检索一次 —— 否则这条测试会变成「因为没检索所以没命中」的假绿")
        .isEqualTo(1);
    assertThat(run.sink.notice())
        .as("★ 附件没进来时不许说「知识库里没有相关内容」")
        .contains("长文档分段测试.md")
        .doesNotContain("知识库里没有与这个问题相关的内容");
  }

  /** 反证的另一半：附件一切正常时，文案必须还是原来那句（否则等于把所有零命中都怪到附件上）。 */
  @Test
  void aHealthyAttachmentKeepsTheOriginalWording() {
    assertThat(runWithBrokenAttachment(null).sink.notice())
        .as("附件没问题时仍应指向知识库")
        .contains("知识库里没有与这个问题相关的内容");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  @Test
  void referenceBudgetDropsTheTailAndSaysHowMany() {
    FixedSearchPort search =
        new FixedSearchPort(
            List.of(
                new SegmentSearchPort.Scored(SEGMENT_1, 0.9),
                new SegmentSearchPort.Scored(SEGMENT_2, 0.8)),
            List.of());
    search.contentRepeat = 3; // 每段 42 字
    RetrievalParams params =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.0, 5, NoRefStrategy.FREE_ANSWER);

    Run run = run(params, search, List.<Float>of(), false, null, null, 50);

    assertThat(run.refs())
        .as("预算 50 字只装得下第 1 条（42 字），第 2 条要丢掉 —— 而 UI 上的引用数必须与真正进了上下文的条数一致")
        .hasSize(1);
    assertThat(run.sink.notice())
        .as("★ 丢掉的条数必须说出来：用户看到只有 1 条引用，却不知道自己问的那份文档被省掉了，会以为是「没检到」")
        .contains("省略 1 条");
  }

  @Test
  void aBudgetSmallerThanOneReferenceDoesNotBecomeZeroReferences() {
    FixedSearchPort search =
        new FixedSearchPort(
            List.of(
                new SegmentSearchPort.Scored(SEGMENT_1, 0.9),
                new SegmentSearchPort.Scored(SEGMENT_2, 0.8)),
            List.of());
    search.contentRepeat = 3;
    RetrievalParams params =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.0, 5, NoRefStrategy.FREE_ANSWER);

    Run run = run(params, search, List.<Float>of(), false, null, null, 10);

    assertThat(run.refs()).as("预算比第一条正文还小 = 配置写错了，不该表现为「检索不到东西」").hasSize(2);
    assertThat(run.sink.notice()).as("这种情况没有「省略」，也就不该编一句出来").isNull();
  }

  private record Run(
      List<SourceRef> refs,
      RecordingSink sink,
      AtomicInteger modelCalls,
      AtomicInteger embeddingCalls,
      FixedSearchPort search) {}

  /**
   * 一轮的「会话 / 策略 / 附件」三件套。
   *
   * @param conversationId 会话 id（非空 ⇒ 检索范围带会话域；★ 它本身**不**能说明"这一轮有附件"）
   * @param policy 通用问答策略替身（{@code null} = 不装配 ⇒ 等价于改造前行为）
   * @param hasAttachments 这个会话有没有登记过附件
   * @param preparedKbIds 这一轮绑定的知识库（空 = 通用问答未绑库）
   */
  private record Scenario(
      UUID conversationId,
      ChatPolicyResolver policy,
      boolean hasAttachments,
      List<UUID> preparedKbIds,
      /** 附件「现在检索不到」时给用户看的那句话；{@code null} = 附件一切正常。 */
      String attachmentFailure) {}

  private static Run run(RetrievalParams params, List<SegmentSearchPort.Scored> hits) {
    return run(params, new FixedSearchPort(hits, List.of()), List.of(), true);
  }

  private static Run run(RetrievalParams params, FixedSearchPort search, List<Float> vector) {
    return run(params, search, vector, true, null);
  }

  private static Run run(
      RetrievalParams params,
      FixedSearchPort search,
      List<Float> vector,
      boolean wireEmbeddingPort) {
    return run(params, search, vector, wireEmbeddingPort, null);
  }

  private static Run run(
      RetrievalParams params,
      FixedSearchPort search,
      List<Float> vector,
      boolean wireEmbeddingPort,
      UUID failingEmbeddingModel) {
    return run(params, search, vector, wireEmbeddingPort, failingEmbeddingModel, null);
  }

  /**
   * 带"会话 / 策略 / 附件"三件套的一轮。
   *
   * @param scenario {@code null} = 本类其他测试的老形状（无会话、无策略、无附件）
   */
  private static Run run(
      RetrievalParams params,
      FixedSearchPort search,
      List<Float> vector,
      boolean wireEmbeddingPort,
      UUID failingEmbeddingModel,
      Scenario scenario) {
    return run(params, search, vector, wireEmbeddingPort, failingEmbeddingModel, scenario, null);
  }

  /** 带引用正文预算的一轮（`#1.8` 的上下文预算闸门）。{@code null} = 用类里的默认值。 */
  private static Run run(
      RetrievalParams params,
      FixedSearchPort search,
      List<Float> vector,
      boolean wireEmbeddingPort,
      UUID failingEmbeddingModel,
      Scenario scenario,
      Integer referenceBudgetChars) {
    List<SourceRef> captured = new ArrayList<>();
    RecordingStore store =
        new RecordingStore(params, scenario == null ? List.of(KB_ID) : scenario.preparedKbIds());
    AtomicInteger modelCalls = new AtomicInteger();
    ChatStreamPort model =
        (modelId, prompt, timeout, callback) -> {
          modelCalls.incrementAndGet();
          callback.onChunk("假模型答案");
          callback.onUsage(10, 5);
          callback.onEnd(ChatStreamPort.End.COMPLETE, null);
        };
    ChatService service =
        new ChatService(
            store,
            search,
            new KnownScope(),
            model,
            new ChatStopSignal(),
            ChatStreamProperties.defaults(),
            UNRESTRICTED_EGRESS,
            null);
    ChatServiceRetrieval.wire(service);
    AtomicInteger embeddingCalls = new AtomicInteger();
    if (wireEmbeddingPort) {
      service.setEmbeddingPort(
          new EmbeddingPort() {
            @Override
            public List<float[]> embed(UUID modelId, List<String> texts) {
              embeddingCalls.incrementAndGet();
              if (failingEmbeddingModel != null && failingEmbeddingModel.equals(modelId)) {
                throw new IllegalStateException("测试注入：向量模型未加载");
              }
              float[] v = new float[vector.size()];
              for (int i = 0; i < vector.size(); i++) {
                v[i] = vector.get(i);
              }
              return List.of(v);
            }
          });
    }
    UUID conversationId = null;
    if (scenario != null) {
      conversationId = scenario.conversationId();
      if (scenario.policy() != null) {
        service.setPolicyResolver(scenario.policy());
      }
      service.setConversationAttachments(
          new FixedAttachments(
              scenario.conversationId(), scenario.hasAttachments(), scenario.attachmentFailure()));
    }
    if (referenceBudgetChars != null) {
      service.setReferenceBudgetChars(referenceBudgetChars);
    }
    RecordingSink sink = new RecordingSink();
    service.streamTurn(
        store.prepare(conversationId, APP_ID, QUESTION, UUID.randomUUID()), QUESTION, sink);
    captured.addAll(sink.refs());
    return new Run(captured, sink, modelCalls, embeddingCalls, search);
  }

  /**
   * 一个"通用问答没开召回"的策略替身。
   *
   * <p>★ 只 stub 决定开关的那一格：本轮参数由 {@code PreparedTurn} 直接带上（见下面的便捷 {@code run}），所以 {@code
   * retrievalThreshold} / {@code retrievalTopN} 这一次**不会被消费**。若哪天它被消费了，说明"参数从哪来"这条口径变了 ——
   * 本类的其他断言会先红。
   */
  private static ChatPolicyResolver switchedOffPolicy() {
    ChatPolicyResolver policy = mock(ChatPolicyResolver.class);
    when(policy.retrievalEnabled()).thenReturn(false);
    return policy;
  }

  /** 便捷形状：会话已存在、策略固定、只关心"有没有附件"。 */
  private static Run run(
      ChatPolicyResolver policy, FixedSearchPort search, boolean hasAttachments) {
    return run(
        // ★ 必须显式 FULLTEXT：`effectiveRetrieval` 优先用 PreparedTurn 带的参数（平台默认是 VECTOR），
        //   换成 platformDefaults() 会让全文那一路根本不跑 —— 那一红与开关无关，是夹具选错了路。
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.30, 5, NoRefStrategy.FREE_ANSWER),
        search,
        List.of(),
        false,
        null,
        new Scenario(CONVERSATION_ID, policy, hasAttachments, List.of(), null));
  }

  /** 便捷形状：会话已存在、策略固定、有附件，但**附件本身检索不到**（`#358`）。 */
  private static Run runWithBrokenAttachment(String failure) {
    return run(
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.30, 5, NoRefStrategy.FREE_ANSWER),
        new FixedSearchPort(List.of(), List.of()),
        List.of(),
        false,
        null,
        new Scenario(CONVERSATION_ID, null, true, List.of(), failure));
  }

  private static SegmentSearchPort.Scored hit(UUID id, double score) {
    return new SegmentSearchPort.Scored(id, score);
  }

  /**
   * 检索端口：固定返回给定命中；`load` 给每条命中一个可组装的 Card。
   *
   * <p>★ 同时支持两路：fullText 返回 fullTextHits、vectorSearch 返回 vectorHits，各带一个调用计数器， 供 VECTOR / FULLTEXT
   * / HYBRID 三种模式断言"哪一路真的被跑了"。
   */
  private static final class FixedSearchPort implements SegmentSearchPort {

    private final List<Scored> fullTextHits;
    private final List<Scored> vectorHits;
    private final boolean conversationScope;
    private final List<UUID> kbIds;
    private final Map<UUID, UUID> embeddingModels;
    private final Map<UUID, List<Scored>> vectorHitsByKb;
    private RetrievalScope lastVectorScope;
    private UUID lastVectorModelId;

    /**
     * 哪些分段属于会话附件（{@code load} 对它们返回 {@code kbId = null}）。
     *
     * <p>★ 可变字段是故意的：它是"这一轮有没有会话域命中"的唯一开关，各测试按需打开。
     */
    private Set<UUID> conversationSegments = Set.of();

    /** ★ 每条分段正文重复几次 —— 用来把"引用正文超预算"这条造出来（默认 1 = 短的正常正文）。 */
    private int contentRepeat = 1;

    /** ★ 最近一次构造范围时传的 `includeAllVisible`；`null` = 这一轮没走到范围构造（早退）。 */
    private Boolean lastIncludeAllVisible;

    final AtomicInteger fullTextCalls = new AtomicInteger();
    final AtomicInteger vectorSearchCalls = new AtomicInteger();

    private FixedSearchPort(List<Scored> fullTextHits, List<Scored> vectorHits) {
      this(fullTextHits, vectorHits, false);
    }

    private FixedSearchPort(
        List<Scored> fullTextHits, List<Scored> vectorHits, boolean conversationScope) {
      this(
          fullTextHits,
          vectorHits,
          conversationScope,
          List.of(KB_ID),
          Map.of(KB_ID, MODEL_ID),
          Map.of(KB_ID, vectorHits));
    }

    private FixedSearchPort(
        List<Scored> fullTextHits,
        List<Scored> vectorHits,
        boolean conversationScope,
        List<UUID> kbIds,
        Map<UUID, UUID> embeddingModels,
        Map<UUID, List<Scored>> vectorHitsByKb) {
      this.fullTextHits = fullTextHits;
      this.vectorHits = vectorHits;
      this.conversationScope = conversationScope;
      this.kbIds = kbIds;
      this.embeddingModels = embeddingModels;
      this.vectorHitsByKb = vectorHitsByKb;
    }

    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      if (conversationScope) {
        return new RetrievalScope(
            kbIds,
            null,
            UUID.fromString("0a000000-0000-4000-8000-0000000000d1"),
            UUID.fromString("0a000000-0000-4000-8000-0000000000d2"));
      }
      // ★ 真实实现里 `conversationId` 非空 ⇒ 范围带上会话域（即便一个库都没绑）。
      //   替身必须一样：否则"通用问答 + 只有附件、没绑库"这个形状在夹具里构造不出来
      //   （scope 会是空 ⇒ 在范围那一步就早退零引用，测不到召回开关这一条）。
      //   ★ ownerId 必须一起给：`RetrievalScope` 的不变量是"会话域非空 ⇒ ownerId 非空"，
      //   缺了它构造就抛 ⇒ 被 retrieve 的 catch 吞成"零引用"（9 个测试一起红，原因却与它们无关）。
      return conversationId == null
          ? RetrievalScope.shared(kbIds)
          : new RetrievalScope(kbIds, null, conversationId, KnownScope.ME);
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return kbIds;
    }

    /**
     * 四参版本：只记录调用方要不要「全部可见库」，范围本身照旧。
     *
     * <p>★ 真实实现的判据（`JdbcSegmentSearchQuery#visibleKbIds`）是"传 false 且清单为空 ⇒ 连库都不查"，
     * 而替身这里必须能**看到**这个开关，否则 `attachmentExemptionStopsAtTheConversationScope` 无从断言。
     */
    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested,
        UUID conversationId,
        boolean includePersonal,
        boolean includeAllVisible) {
      lastIncludeAllVisible = includeAllVisible;
      return retrievalScope(requested, conversationId, includePersonal);
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      fullTextCalls.incrementAndGet();
      return fullTextHits;
    }

    /**
     * ★ 检索现在走 {@code RetrievalService}，它在出结果前会做一次「域感知的可见性复核」。
     *
     * <p>这个假端口的可见性由 {@code retrievalScope} / {@code visibleKbIds} 决定，复核这一步对它恒真。
     * 不补这个方法的话默认实现会<strong>明确抛</strong>（那是为了防生产实现静默丢候选）， 而抛出来在对话侧表现为「零引用」，看起来像"检索坏了"。
     */
    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, RetrievalScope scope) {
      return Set.copyOf(candidateIds);
    }

    public List<Card> load(Collection<UUID> segmentIds) {
      return segmentIds.stream()
          .map(
              id ->
                  new Card(
                      id,
                      "分段 " + id,
                      "差旅住宿标准为每人每天500元".repeat(contentRepeat),
                      "差旅制度",
                      "制度库",
                      UUID.randomUUID(),
                      // ★ 会话附件没有库行（`kb_segment.kb_id` 是 NULL）—— 这个 null 就是"会话域"的判据本身
                      conversationSegments.contains(id) ? null : KB_ID,
                      ClassificationLevel.INTERNAL))
          .toList();
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      return embeddingModels;
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      lastVectorScope = query.scope();
      lastVectorModelId = query.embeddingModelId();
      vectorSearchCalls.incrementAndGet();
      UUID kbId = query.scope().sharedKbIds().getFirst();
      return vectorHitsByKb.getOrDefault(kbId, vectorHits);
    }
  }

  /** 范围端口：身份正常（me + 密级上限都在），于是 retrieve 会真的走到全文检索。 */
  private static final class KnownScope implements AccessScopePort {

    private static final UUID ME = UUID.fromString("0a000000-0000-4000-8000-0000000000c1");

    @Override
    public ScopeFilter currentScope() {
      return new ScopeFilter(
          ME, DataScope.OWN, Set.of("ADMIN"), ClassificationLevel.INTERNAL, Set.of());
    }

    @Override
    public boolean canSee(OwnedResource resource) {
      return true;
    }
  }

  /**
   * 附件端口的替身：只回答"这个会话有没有附件"。
   *
   * <p>★ 另外三个方法直接抛 —— 本类只测检索链路读不读"有没有附件"，任何写入/清理动作跑到这里都是测试本身写错了。
   */
  private static final class FixedAttachments implements ConversationAttachmentPort {

    private final UUID conversationId;
    private final boolean has;

    /** 附件「现在检索不到」时给用户看的那句话；{@code null} = 附件一切正常（走原本文案）。 */
    private final String failure;

    private FixedAttachments(UUID conversationId, boolean has) {
      this(conversationId, has, null);
    }

    private FixedAttachments(UUID conversationId, boolean has, String failure) {
      this.conversationId = conversationId;
      this.has = has;
      this.failure = failure;
    }

    @Override
    public boolean hasAttachments(UUID id) {
      return has && conversationId != null && conversationId.equals(id);
    }

    @Override
    public Optional<String> describeAttachmentFailure(UUID id) {
      if (!has || conversationId == null || !conversationId.equals(id)) {
        return Optional.empty();
      }
      return Optional.ofNullable(failure);
    }

    @Override
    public UUID register(
        UUID conversationId,
        UUID ownerId,
        UUID attachmentId,
        String displayName,
        byte[] content,
        ClassificationLevel classification) {
      throw new UnsupportedOperationException("本类不测附件登记");
    }

    @Override
    public PurgeResult purgeConversationAttachments(UUID conversationId) {
      throw new UnsupportedOperationException("本类不测附件清理");
    }

    @Override
    public PurgeResult previewConversationAttachments(List<UUID> conversationIds) {
      throw new UnsupportedOperationException("本类不测附件清理");
    }
  }

  /** 落库侧替身：只提供 prepare（本类不测落库）。 */
  private static final class RecordingStore implements ChatTurnStore {

    private final RetrievalParams params;
    private final List<UUID> kbIds;

    private RecordingStore(RetrievalParams params) {
      this(params, List.of(KB_ID));
    }

    private RecordingStore(RetrievalParams params, List<UUID> kbIds) {
      this.params = params;
      this.kbIds = kbIds;
    }

    @Override
    public ChatTurnWriter.PreparedTurn prepare(
        UUID conversationId, UUID appId, String question, UUID userId) {
      UUID assistantMessageId = UUID.randomUUID();
      return new ChatTurnWriter.PreparedTurn(
          // ★ 入参非空就用入参：本类要断言"这一轮是不是同一个会话"（附件查询按会话 id 走），
          //   恒随机的话，附件替身永远认不出这一轮属于哪个会话 ⇒ 测不到附件那条路。
          conversationId != null ? conversationId : UUID.randomUUID(),
          assistantMessageId,
          UUID.randomUUID(),
          "trace_" + assistantMessageId,
          MODEL_ID.toString(),
          MODEL_ID,
          kbIds,
          false,
          false,
          APP_ID,
          "你是法务助手",
          List.of(),
          params);
    }

    @Override
    public ChatTurnWriter.PreparedTurn prepareRegenerate(UUID targetMessageId, UUID userId) {
      throw new UnsupportedOperationException("本类不测重新生成");
    }

    @Override
    public String questionOf(UUID userMessageId) {
      throw new UnsupportedOperationException("本类不测重新生成");
    }

    @Override
    public List<cn.lincoln.aiplatform.platform.model.ChatMessage> historyBefore(
        UUID conversationId, UUID userMessageId, int maxMessages) {
      throw new UnsupportedOperationException("本类不测多轮");
    }

    @Override
    public int flush(UUID messageId, String fullTextSoFar, int persistedChars) {
      return fullTextSoFar.length();
    }

    @Override
    public void finish(
        UUID messageId,
        StreamTerminationPath path,
        String fullTextSoFar,
        int persistedChars,
        List<SourceRef> references,
        TokenUsage usage,
        long durationMs,
        Integer firstTokenMs,
        String failReason,
        String failHint) {}
  }

  /** 事件出口：记 refs / delta / done。 */
  private static final class RecordingSink implements ChatService.EventSink {

    private final List<StreamEvent> events = new ArrayList<>();

    @Override
    public void onEvent(StreamEvent event) {
      events.add(event);
    }

    private List<SourceRef> refs() {
      return events.stream()
          .filter(event -> event.kind() == EventKind.REFS)
          .map(event -> (StreamEvent.References) event)
          .findFirst()
          .map(StreamEvent.References::refs)
          .orElseThrow(() -> new AssertionError("出口上没有收到 refs 帧"));
    }

    /**
     * 零命中时那句给用户看的原因（有引用时恒为 {@code null}）。
     *
     * <p>★ 单独取一次 refs 帧再读 {@code notice()}，而不是用 {@code map(...).findFirst()}： 后者遇到 null 元素会抛
     * NPE，而"没有原因"恰恰是这里最常见的合法值。
     */
    private String notice() {
      StreamEvent.References refsFrame =
          events.stream()
              .filter(event -> event.kind() == EventKind.REFS)
              .map(StreamEvent.References.class::cast)
              .findFirst()
              .orElseThrow(() -> new AssertionError("出口上没有收到 refs 帧"));
      return refsFrame.notice();
    }

    private List<String> deltas() {
      return events.stream()
          .filter(event -> event.kind() == EventKind.DELTA)
          .map(event -> ((StreamEvent.Delta) event).text())
          .toList();
    }

    private StreamEvent.StreamDoneEvent done() {
      return (StreamEvent.StreamDoneEvent)
          events.stream()
              .filter(event -> event.kind() == EventKind.DONE)
              .reduce((first, second) -> second)
              .orElseThrow(() -> new AssertionError("出口上没有收到 done 帧"));
    }
  }
}
