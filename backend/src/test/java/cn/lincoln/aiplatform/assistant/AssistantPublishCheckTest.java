package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.assistant.application.AssistantBundleView;
import cn.lincoln.aiplatform.assistant.application.AssistantService;
import cn.lincoln.aiplatform.assistant.application.AssistantView;
import cn.lincoln.aiplatform.assistant.application.PublishCheckResult;
import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.AssistantType;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ModelStatusPort;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 第 15 周判据①②③的读数（不变量 {@code AS-I1~I5}，{@code 02 §273-277}）。
 *
 * <h3>为什么这些必须有读数</h3>
 *
 * <p>发布校验最容易做成"永远通过"：每一项都写成 {@code true}，界面上看起来一样（都能发布）， 而故障会推迟到<strong>使用者提问时</strong>才出现 —— 那正是
 * {@code 02 §279} 说 AS-I2 要解决的那个故障。 所以每一项失败都要有一条断言，且断言的是 <strong>key + 是否通过 +
 * 原因里有没有"照着改"的那句话</strong>。
 *
 * <p>★ 全部用手写假实现（本仓风格：不引 Mockito）：假模型目录只回答"哪个模型加载好了"、假检索端口只回答"哪些库可用"， 于是每一种不通过都能被单独造出来。
 */
class AssistantPublishCheckTest {

  private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final UUID MODEL = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
  private static final UUID KB_OK = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
  private static final UUID KB_BAD = UUID.fromString("00000000-0000-4000-8000-0000000000c2");

  private final FakeAssistants assistants = new FakeAssistants();
  private final FakeModels models = new FakeModels();
  private final FakeSegments segments = new FakeSegments();
  private final FakeScope scope = new FakeScope();

  private final AssistantService service =
      new AssistantService(assistants, scope, models, segments);

  /** 建一个"各项都过"的简单应用；各用例只破坏其中一项。 */
  private Assistant publishable() {
    models.put(MODEL, "本地小模型", true);
    segments.usable.add(KB_OK);
    Assistant assistant =
        new Assistant(
            UUID.randomUUID(),
            "差旅助手",
            "🤖",
            "回答差旅制度问题",
            AssistantType.SIMPLE,
            MODEL,
            "你是差旅制度助手，只依据资料回答",
            OWNER,
            OWNER);
    assistant.applyConfig(
        assistant.name(),
        assistant.icon(),
        assistant.description(),
        MODEL,
        assistant.rolePrompt(),
        true,
        false,
        "你好，有什么可以帮你？",
        List.of("住宿标准是多少"),
        List.of("行政"),
        OWNER);
    assistant.applyRetrieval(
        RetrievalMode.HYBRID, new BigDecimal("0.60"), 5, NoRefStrategy.REFUSE, 3, true, 3, OWNER);
    assistant.bind(List.of(KB_OK), List.of(), OWNER);
    return assistants.save(assistant);
  }

  private PublishCheckResult.Item itemOf(PublishCheckResult result, String key) {
    return result.items().stream()
        .filter(item -> item.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("校验项里没有 " + key + "：" + result.items()));
  }

  // ────────────────────────── 每一项都要能不过 ──────────────────────────

  @Test
  void passesWhenEverythingIsConfigured() {
    Assistant app = publishable();

    PublishCheckResult result = service.checkBeforePublish(app.id());

    assertThat(result.passed()).isTrue();
    assertThat(result.items())
        .extracting(PublishCheckResult.Item::key)
        .as("★ 五项与 mock 的 key 逐字一致（三方一致：契约类型 · mock 口径 · 后端实现）")
        .containsExactly("model", "knowledge", "rolePrompt", "retrieval", "prologue");
    assertThat(result.failureSummary()).isEmpty();
  }

  /** AS-I2：★ 本周的核心 —— "绑了一个没加载完的模型"必须在发布时就被拦住。 */
  @Test
  void blocksWhenBoundModelIsNotLoaded() {
    Assistant app = publishable();
    models.put(MODEL, "本地小模型", false);

    PublishCheckResult result = service.checkBeforePublish(app.id());

    assertThat(result.passed()).isFalse();
    PublishCheckResult.Item model = itemOf(result, "model");
    assertThat(model.passed()).isFalse();
    assertThat(model.detail()).as("失败原因要能照着改").contains("本地小模型").contains("加载");
  }

  /** 模型被删掉 ⇒ 原因必须与"没加载"分得开（否则排查方向会指错）。 */
  @Test
  void blocksWhenBoundModelIsGone() {
    Assistant app = publishable();
    models.snapshots.clear();

    PublishCheckResult.Item model = itemOf(service.checkBeforePublish(app.id()), "model");

    assertThat(model.passed()).isFalse();
    assertThat(model.detail()).contains("不存在");
  }

  /** AS-I3：绑定的知识库不可用（停用 / 删除 / 不可见）⇒ 拦住。 */
  @Test
  void blocksWhenBoundKnowledgeBaseIsUnavailable() {
    Assistant app = publishable();
    app.bind(List.of(KB_OK, KB_BAD), List.of(), OWNER);
    assistants.save(app);

    PublishCheckResult.Item knowledge = itemOf(service.checkBeforePublish(app.id()), "knowledge");

    assertThat(knowledge.passed()).isFalse();
    assertThat(knowledge.detail()).contains("1").contains("不可用");
  }

  /** AS-I4 的落地口径（与 mock 同口径）：角色设定为空 ⇒ 回答风格不受控。 */
  @Test
  void blocksWhenNoRolePrompt() {
    Assistant app = publishable();
    app.applyConfig(
        app.name(),
        app.icon(),
        app.description(),
        MODEL,
        "   ",
        true,
        false,
        app.prologue(),
        List.of(),
        List.of(),
        OWNER);
    assistants.save(app);

    PublishCheckResult.Item prompt = itemOf(service.checkBeforePublish(app.id()), "rolePrompt");

    assertThat(prompt.passed()).isFalse();
    assertThat(prompt.detail()).contains("角色设定为空");
  }

  @Test
  void blocksWhenPrologueIsEmpty() {
    Assistant app = publishable();
    app.applyConfig(
        app.name(),
        app.icon(),
        app.description(),
        MODEL,
        app.rolePrompt(),
        true,
        false,
        "",
        List.of(),
        List.of(),
        OWNER);
    assistants.save(app);

    assertThat(itemOf(service.checkBeforePublish(app.id()), "prologue").passed()).isFalse();
  }

  /** AS-I5：★ 读的是<strong>存下来的值</strong>，所以能造出"写入路径没拦住"的存量数据 —— 这一项正是为它留的。 */
  @Test
  void blocksWhenStoredRetrievalParamsAreOutOfRange() {
    Assistant app = publishable();
    app.applyRetrieval(
        RetrievalMode.HYBRID, new BigDecimal("1.50"), 99, NoRefStrategy.REFUSE, 9, true, 3, OWNER);
    assistants.save(app);

    PublishCheckResult.Item retrieval = itemOf(service.checkBeforePublish(app.id()), "retrieval");

    assertThat(retrieval.passed()).isFalse();
    assertThat(retrieval.detail()).contains("0–1").contains("1–50");
  }

  /** 写入路径也要拦越界（不静默夹紧：收下再按别的值跑，用户看到的与生效的是两回事）。 */
  @Test
  void writePathRejectsOutOfRangeRetrieval() {
    Assistant app = publishable();
    AssistantService.Draft draft =
        new AssistantService.Draft(
            "差旅助手",
            "🤖",
            "回答差旅制度问题",
            AssistantType.SIMPLE,
            MODEL,
            app.rolePrompt(),
            List.of(KB_OK),
            List.of(),
            new AssistantService.RetrievalDraft("HYBRID", 0.6, 99, "REFUSE", 3, true, 3),
            true,
            false,
            "你好",
            List.of(),
            List.of(),
            null);

    assertThatThrownBy(() -> service.update(app.id(), draft, OWNER))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("TopN 应在 1–50 之间");
  }

  // ────────────────────────── 发布：校验不过就不许发 ──────────────────────────

  @Test
  void publishIsRejectedWith6001AndStateStaysUnchanged() {
    Assistant app = publishable();
    models.put(MODEL, "本地小模型", false);

    assertThatThrownBy(() -> service.publish(app.id(), true, OWNER))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).code())
                    .as("6001 = PUBLISH_CHECK_FAILED（契约 types.ts:74 与本枚举两侧一致）")
                    .isEqualTo(ErrorCode.PUBLISH_CHECK_FAILED))
        .hasMessageContaining("发布前校验未通过");

    assertThat(assistants.findById(app.id()).orElseThrow().published())
        .as("★ 校验不过时状态一个字节都不变 —— 不存在「发布一半」")
        .isFalse();
  }

  @Test
  void publishSucceedsWhenCheckPasses() {
    Assistant app = publishable();

    AssistantView view = service.publish(app.id(), true, OWNER);

    assertThat(view.published()).isTrue();
    assertThat(assistants.findById(app.id()).orElseThrow().published()).isTrue();
  }

  /** 取消发布不校验：下线一个应用不该被配置校验拦住。 */
  @Test
  void unpublishIsNeverBlockedByCheck() {
    Assistant app = publishable();
    service.publish(app.id(), true, OWNER);
    models.snapshots.clear();

    assertThatCode(() -> service.publish(app.id(), false, OWNER)).doesNotThrowAnyException();
    assertThat(assistants.findById(app.id()).orElseThrow().published()).isFalse();
  }

  // ────────────────────────── 判据② type 创建后不可改 ──────────────────────────

  /**
   * ★★ 判据②：更新时把 {@code type} 换成 {@code WORKFLOW} ⇒ 类型<strong>一个字节都不变</strong>。
   *
   * <p>这条断言值钱的地方：它在<strong>接口层</strong>证明"传了也不生效"，而不是靠"实体没有 setter"这种要读代码才能确认的事。
   * 同一条用例顺带钉住"部分更新只改传了的字段"（没传的 modelId / kbIds / quickQuestions 都保持原值）。
   */
  @Test
  void typeCannotBeChangedByUpdate() {
    Assistant app = publishable();

    AssistantService.Draft tamper =
        new AssistantService.Draft(
            "改个名",
            null,
            null,
            AssistantType.WORKFLOW,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    AssistantView updated = service.update(app.id(), tamper, OWNER);

    assertThat(updated.type()).isEqualTo("SIMPLE");
    assertThat(updated.name()).isEqualTo("改个名");
    assertThat(updated.modelId()).as("没传 ⇒ 保持原值（契约里 PUT 是 Partial）").isEqualTo(MODEL);
    assertThat(updated.kbIds()).containsExactly(KB_OK);
    assertThat(updated.quickQuestions()).containsExactly("住宿标准是多少");
  }

  // ────────────────────────── 判据③ 导出包 ──────────────────────────

  @Test
  void exportCarriesConfigAndDependencyList() {
    Assistant app = publishable();

    AssistantBundleView bundle = service.export(app.id(), false);

    assertThat(bundle.format()).isEqualTo("assistant-bundle@1");
    assertThat(bundle.config().type()).isEqualTo("SIMPLE");
    assertThat(bundle.config().modelId()).isEqualTo(MODEL);
    assertThat(bundle.dependencies())
        .extracting(AssistantBundleView.Dependency::kind)
        .containsExactly("MODEL", "KNOWLEDGE_BASE");
    assertThat(bundle.dependencies()).allMatch(AssistantBundleView.Dependency::satisfied);
  }

  /** 模型没加载 ⇒ 依赖项不满足，且**带上缺了会怎样**（没有它，导入方只看到一个红点）。 */
  @Test
  void exportMarksUnsatisfiedModelWithDegrade() {
    Assistant app = publishable();
    models.put(MODEL, "本地小模型", false);

    AssistantBundleView.Dependency model =
        service.export(app.id(), false).dependencies().stream()
            .filter(dep -> dep.kind().equals("MODEL"))
            .findFirst()
            .orElseThrow();

    assertThat(model.satisfied()).isFalse();
    assertThat(model.degrade()).contains("无法回答");
  }

  /** 看不见的应用 ⇒ 404（不是 403：403 等于告诉对方"这个 id 存在"）。 */
  @Test
  void invisibleAssistantIsNotFound() {
    Assistant app = publishable();
    scope.visible = false;

    assertThatThrownBy(() -> service.get(app.id()))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static final class FakeAssistants implements AssistantRepository {

    private final Map<UUID, Assistant> store = new LinkedHashMap<>();

    @Override
    public Assistant save(Assistant assistant) {
      store.put(assistant.id(), assistant);
      return assistant;
    }

    @Override
    public Optional<Assistant> findById(UUID id) {
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Assistant> findByClientRequestIdAndOwnerId(String key, UUID ownerId) {
      return store.values().stream()
          .filter(a -> key.equals(a.clientRequestId()) && ownerId.equals(a.ownerId()))
          .findFirst();
    }
  }

  private static final class FakeModels implements ModelStatusPort {

    private final Map<UUID, ModelSnapshot> snapshots = new LinkedHashMap<>();

    void put(UUID id, String name, boolean loaded) {
      snapshots.put(id, new ModelSnapshot(id, name, loaded));
    }

    @Override
    public Optional<ModelSnapshot> find(UUID modelId) {
      return Optional.ofNullable(snapshots.get(modelId));
    }
  }

  /** 假检索端口：只回答"哪些库可用"（AS-I3 走的就是这个方法），其余方法本测试用不到。 */
  private static final class FakeSegments implements SegmentSearchPort {

    private final Set<UUID> usable = new LinkedHashSet<>();

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return requested.stream().filter(usable::contains).toList();
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本测试不用向量路");
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      throw new UnsupportedOperationException("本测试不用向量路");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("本测试不用向量路");
    }

    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本测试不用向量路");
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      return new ArrayList<>();
    }
  }

  private static final class FakeScope implements AccessScopePort {

    private boolean visible = true;

    @Override
    public ScopeFilter currentScope() {
      return ScopeFilter.nobody();
    }

    @Override
    public boolean canSee(OwnedResource resource) {
      return visible;
    }
  }
}
