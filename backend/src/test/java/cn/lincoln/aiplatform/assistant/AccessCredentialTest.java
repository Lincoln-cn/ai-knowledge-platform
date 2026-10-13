package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.assistant.application.AccessCredentialService;
import cn.lincoln.aiplatform.assistant.application.AccessCredentialView;
import cn.lincoln.aiplatform.assistant.application.PublicCredentialResolver;
import cn.lincoln.aiplatform.assistant.domain.AccessCredential;
import cn.lincoln.aiplatform.assistant.domain.AccessCredentialRepository;
import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.AssistantType;
import cn.lincoln.aiplatform.assistant.domain.InvalidCredentialException;
import cn.lincoln.aiplatform.identity.FakeAccessScope;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 第 16 周（0.4.4）判据 ①②③ 的读数 —— <strong>免登录访问凭证 + 创建时冻结密级</strong>。
 *
 * <h3>三条判据各自落在哪条断言上</h3>
 *
 * <ol>
 *   <li><b>撤销后立即失效</b>：{@link #revokedCredentialIsRejectedImmediately()} —— 撤销之后<strong>同一个
 *       token</strong>再解析即被拒。它同时是"按 token 的缓存必须能被撤销动作清掉"的读数（本实现确实缓存，见 {@code
 *       PublicCredentialResolver}）。
 *   <li><b>创建时冻结</b>：{@link #maxClassificationComesFromCreatorClearanceNotRequestBody()}（取请求者
 *       clearance、忽略请求体） + {@link #contentUpgradedAboveFrozenValueDisappearsFromTheSameToken()}（升密
 *       ⇒ 原链接不再返回那部分）。
 *   <li><b>每次访问实时再滤</b>：{@link #secondAccessSeesLessAfterContentIsUpgraded()} —— 同一条 token
 *       两次访问之间把内容升密， 第二次的可见集合<strong>收窄</strong>。★ 它正是金丝雀要破坏的那一条（破坏方式 = 复用创建时算出来的结果）。
 * </ol>
 *
 * <h3>四种失败原因必须分得开</h3>
 *
 * <p>{@link #fourFailureReasonsAreDistinguishable()}：不存在 / 已撤销 / 已过期 / 越权 —— 各断言 <strong>码 +
 * 文案关键字</strong>；四态里的"已停用"在 {@link #disabledCredentialIsRejectedButRecoverable()} 里断言。
 * 全返同一个码会让排障时只能猜，而"已过期"与"已撤销"的处置完全不同 （前者等或重新生成，后者只能重新生成）。
 *
 * <p>★★ <strong>2026-10-14（第 17 周开工）为什么改这组断言</strong>：这四种"公开令牌失效"此前一律抛 {@code FORBIDDEN} / {@code
 * NOT_FOUND}（码位不够时的临时口径，见 {@code AccessCredentialService} 类注释的订正段）。 而契约与后端枚举两侧已同批补上 {@code
 * PUBLIC_TOKEN_UNKNOWN / REVOKED / EXPIRED / DISABLED}（{@code 40401}–{@code 40404}，{@code #41} 的
 * (a) 口径）， 前端 {@code utils/public-gate.ts} 正是<strong>按这四个码</strong>分四态文案 ⇒ 后端不分开 emit
 * 的后果是四态<strong>全部静默退化</strong>成 "打不开这条链接"。所以下面三条旧断言（{@code NOT_FOUND} / {@code FORBIDDEN}）被改成各自的码
 * —— 这是"改码"的读数，不是措辞调整。 ★ 唯一<strong>不许</strong>跟着改的是"越权"那一格：它是"对方是合法登录用户、却不该碰这条凭证"，与令牌失效无关（{@code
 * 403} 照旧）。
 *
 * <h3>为什么用假实现（本仓风格：不引 Mockito）</h3>
 *
 * <p>每种不通过都要能被单独造出来；而<strong>真 SQL 与真密级谓词</strong>由 {@code AccessCredentialFilteringTest} （真 PG + 真
 * identity 适配器）负责。★ 本类<strong>验不了</strong> SQL —— 而这正是"判定只有一处实现"的代价与收益：
 * 假实现里那份"按冻结值过滤"只回答"冻结值有没有被真的用到"。
 */
class AccessCredentialTest {

  private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
  private static final UUID MODEL = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
  private static final UUID KB = UUID.fromString("00000000-0000-4000-8000-0000000000c1");

  private final FakeAssistants assistants = new FakeAssistants();
  private final FakeCredentials credentials = new FakeCredentials();
  private final FakeSegments segments = new FakeSegments();
  private final FakeAccessScope accessScope = new FakeAccessScope();
  private final PublicCredentialResolver resolver =
      new PublicCredentialResolver(assistants, credentials);

  private final AccessCredentialService service =
      new AccessCredentialService(assistants, credentials, segments, accessScope, resolver);

  /** 建一个已发布、绑了一个知识库的应用（并把"当前请求者"设成它的归属人）。 */
  private Assistant app() {
    // ★ 替身要显式知道"当前请求者是谁"（与真实现从 JWT 的 sub 读出来同口径）：
    //   否则 currentScope() 是 nobody()，"这个应用可见吗"会假成 404
    accessScope.setRequesterId(OWNER);
    Assistant assistant =
        new Assistant(
            UUID.randomUUID(),
            "差旅助手",
            "🤖",
            "回答差旅制度问题",
            AssistantType.SIMPLE,
            MODEL,
            "你是差旅制度助手",
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
        "你好",
        List.of(),
        List.of(),
        OWNER);
    assistant.applyRetrieval(
        RetrievalMode.HYBRID, new BigDecimal("0.60"), 5, NoRefStrategy.REFUSE, 3, true, 3, OWNER);
    assistant.bind(List.of(KB), List.of(), OWNER);
    assistant.publish(true, OWNER);
    return assistants.save(assistant);
  }

  private static Set<UUID> idsOf(List<SegmentSearchPort.Scored> hits) {
    return hits.stream()
        .map(SegmentSearchPort.Scored::segmentId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  // ────────────────────────── 判据 ②：创建时冻结 ──────────────────────────

  /**
   * ★ 冻结值 = <strong>创建者此刻的 clearance</strong>，且<strong>请求体传什么都不算</strong>。
   *
   * <p>请求体里塞一个 {@code maxClassification} 是这次要防的具体攻击：调用方把自己的凭证抬到超过自己 ——
   * 那条通道一旦存在，凭证就成了"自建越权"的工具。契约的入参只有 {@code expiresAt}，服务层的 {@code create}
   * 也<strong>压根没有这个入参</strong>（不是"读了再丢掉"）。
   */
  @Test
  void maxClassificationComesFromCreatorClearanceNotRequestBody() {
    Assistant app = app();
    accessScope.setClearance(ClassificationLevel.INTERNAL);

    AccessCredentialView created = service.create(app.id(), null, OWNER);

    assertThat(created.maxClassification())
        .as("回执值必须是创建者此刻的 clearance 机器码（INTERNAL），不是他从角色里'应该能到'的更高值")
        .isEqualTo(ClassificationLevel.INTERNAL.name());
    assertThat(credentials.findById(created.id()).orElseThrow().maxClassification())
        .as("库里那一条也必须是冻结值（回执对了但落库没对，等于没冻结）")
        .isEqualTo(ClassificationLevel.INTERNAL);
  }

  /** 创建者 clearance 就是最高档时照样照抄（不许"夹到中间某一档"）。 */
  @Test
  void frozenValueFollowsWhateverClearanceTheCreatorHas() {
    Assistant app = app();
    accessScope.setClearance(ClassificationLevel.CONFIDENTIAL);

    assertThat(service.create(app.id(), null, OWNER).maxClassification())
        .isEqualTo(ClassificationLevel.CONFIDENTIAL.name());
  }

  // ────────────────────────── 判据 ①：撤销后立即失效 ──────────────────────────

  /**
   * ★★ 撤销是<strong>终态</strong>，且必须<strong>当场</strong>失效：撤销之后同一个 token 立刻解析不出来。
   *
   * <p>它同时是缓存判据：实现按 token 缓存了解析结果（省掉每次访问一次查库），于是撤销必须把那一项清掉。 清不掉的症状是"撤销了还能用一段时间" ——
   * 而"撤销"这个词对使用者的含义是<strong>现在就不能用了</strong>。
   */
  @Test
  void revokedCredentialIsRejectedImmediately() {
    Assistant app = app();
    AccessCredentialView created = service.create(app.id(), null, OWNER);
    // ★ 先访问一次，把解析结果放进缓存 —— 不先访问一次，这条断言证明不了"缓存被清了"
    assertThat(service.preview(app.id(), created.token(), OWNER)).isNotNull();

    service.revoke(app.id(), created.id(), OWNER);

    assertThat(credentials.findById(created.id()).orElseThrow().revokedAt())
        .as("撤销要落 revokedAt（终态的时间戳）")
        .isNotNull();
    assertThatThrownBy(() -> service.preview(app.id(), created.token(), OWNER))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已撤销");
  }

  /** 撤销终态：不能再被启停打开（否则"终态"只是个装饰）。 */
  @Test
  void revokedCredentialCannotBeReEnabled() {
    Assistant app = app();
    AccessCredentialView created = service.create(app.id(), null, OWNER);
    service.revoke(app.id(), created.id(), OWNER);

    assertThatThrownBy(() -> service.setEnabled(app.id(), created.id(), true, OWNER))
        .isInstanceOf(InvalidCredentialException.class)
        .hasMessageContaining("已撤销");
    assertThat(credentials.findById(created.id()).orElseThrow().enabled()).isFalse();
  }

  // ────────────────────────── 四种失败原因 ──────────────────────────

  /**
   * ★ 不存在 / 已撤销 / 已过期 / 越权 —— <strong>四种原因必须能区分</strong>。
   *
   * <p>不区分的代价是排障时只能猜：前三种对调用方的下一步动作完全不同（换链接 / 重新生成 / 等或重新生成）， 而"越权"是唯一一种<strong>对方不该知道这个 token
   * 是否存在</strong>的情形。
   *
   * <p>★★ 第 17 周改码：前两格从 {@code NOT_FOUND} / {@code FORBIDDEN} 改成各自的公开面码（{@code 40401} / {@code
   * 40402} / {@code 40403}）—— <strong>理由与"为什么越权那一格不动"</strong>写在类注释里。★ 本方法是金丝雀的落点： 把任一条改回 {@code
   * FORBIDDEN} ⇒ 这一条必红。
   */
  @Test
  void fourFailureReasonsAreDistinguishable() {
    Assistant app = app();
    AccessCredentialView created = service.create(app.id(), null, OWNER);

    // ① 不存在 / 认不出（40401：连"这个 token 存在过"都不该确认）
    assertThatThrownBy(() -> service.preview(app.id(), "pk_live_根本没有这个token", OWNER))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).code())
                    .isEqualTo(ErrorCode.PUBLIC_TOKEN_UNKNOWN))
        .hasMessageContaining("不存在");

    // ② 已撤销（40402：终态）
    AccessCredentialView second = service.create(app.id(), null, OWNER);
    service.revoke(app.id(), second.id(), OWNER);
    assertThatThrownBy(() -> service.preview(app.id(), second.token(), OWNER))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).code())
                    .isEqualTo(ErrorCode.PUBLIC_TOKEN_REVOKED))
        .hasMessageContaining("已撤销");

    // ③ 已过期（40403：把有效期放到过去）
    AccessCredentialView third = service.create(app.id(), Instant.now().minusSeconds(60), OWNER);
    assertThatThrownBy(() -> service.preview(app.id(), third.token(), OWNER))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).code())
                    .isEqualTo(ErrorCode.PUBLIC_TOKEN_EXPIRED))
        .hasMessageContaining("已过期");

    // ④ 越权：不是归属人 ⇒ 看不见这个凭证（★ 与"不存在"分开：这里对方是合法登录用户，
    //    而拒绝发生在"委派范围只能由发起解析的那个人行使"这一步 —— 与真实现同一句话）
    //    ★ 这一格**故意仍是 403**：令牌是好的，是"人不该碰"（不是四态里的任何一态）
    assertThatThrownBy(() -> service.preview(app.id(), created.token(), OTHER))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.FORBIDDEN))
        .hasMessageContaining("无法以别人的身份");
  }

  /**
   * ★ 认不出的第二种形状：token 是真的，但它属于<strong>另一个应用</strong>。
   *
   * <p>它必须与"目标没了"（{@code 404}）分开：前者是"这条链接对**这个应用**认不出"（重抄一遍没有用，要重新索取），
   * 后者是"链接是对的、它指向的应用已经不存在了"（同一条链接永远打不开）。 前端按码分这两段文案，所以这里也断言码。
   */
  @Test
  void credentialOfAnotherAppIsReportedAsUnknown() {
    Assistant first = app();
    Assistant second = app();
    AccessCredentialView created = service.create(first.id(), null, OWNER);

    assertThatThrownBy(() -> service.preview(second.id(), created.token(), OWNER))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).code())
                    .isEqualTo(ErrorCode.PUBLIC_TOKEN_UNKNOWN))
        .hasMessageContaining("不存在");
  }

  /** 停用（enabled=false）也要拒绝 —— 它与"已撤销"是两件事：停用可再启用、凭证保留。 */
  @Test
  void disabledCredentialIsRejectedButRecoverable() {
    Assistant app = app();
    AccessCredentialView created = service.create(app.id(), null, OWNER);

    service.setEnabled(app.id(), created.id(), false, OWNER);
    assertThatThrownBy(() -> service.preview(app.id(), created.token(), OWNER))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).code())
                    .isEqualTo(ErrorCode.PUBLIC_TOKEN_DISABLED))
        .hasMessageContaining("已停用");

    service.setEnabled(app.id(), created.id(), true, OWNER);
    assertThat(service.preview(app.id(), created.token(), OWNER)).isNotNull();
  }

  // ────────────────────────── 判据 ②/③：升密 ⇒ 可见集合收窄 ──────────────────────────

  /**
   * ★ 判据 ② 的后半：<strong>升密后原链接失效</strong>。
   *
   * <p>冻结值是 {@code INTERNAL}，库里有两条 {@code INTERNAL} 分段；把其中一条抬成 {@code SECRET} （高于冻结值）⇒ 同一条 token
   * <strong>不再返回它</strong>。
   */
  @Test
  void contentUpgradedAboveFrozenValueDisappearsFromTheSameToken() {
    Assistant app = app();
    accessScope.setClearance(ClassificationLevel.INTERNAL);
    AccessCredentialView created = service.create(app.id(), null, OWNER);
    UUID keep = UUID.randomUUID();
    UUID upgraded = UUID.randomUUID();
    segments.put(keep, ClassificationLevel.INTERNAL, "差旅住宿标准");
    segments.put(upgraded, ClassificationLevel.INTERNAL, "差旅住宿标准补充");

    assertThat(idsOf(service.preview(app.id(), created.token(), OWNER)))
        .as("升密之前两条都看得见（冻结值 INTERNAL ⊇ INTERNAL）")
        .containsExactlyInAnyOrder(keep, upgraded);

    segments.put(upgraded, ClassificationLevel.SECRET, "差旅住宿标准补充");

    assertThat(idsOf(service.preview(app.id(), created.token(), OWNER)))
        .as("★ 升密后同一条凭证不再返回那部分内容 —— 链接不是内容快照，是一个受限的查询凭证")
        .containsExactly(keep);
  }

  /**
   * ★★ 判据 ③：<strong>访客每次访问实时再滤</strong>（不是快照）。
   *
   * <p>同一条 token 两次访问之间把所有内容升密 ⇒ 第二次的可见集合<strong>收窄</strong>。 复用"创建时算出来的结果"就做不到这一点 ——
   * 那正是金丝雀要破坏的写法（见交付报告）。
   */
  @Test
  void secondAccessSeesLessAfterContentIsUpgraded() {
    Assistant app = app();
    accessScope.setClearance(ClassificationLevel.SECRET);
    AccessCredentialView created = service.create(app.id(), null, OWNER);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    segments.put(first, ClassificationLevel.PUBLIC, "差旅住宿标准");
    segments.put(second, ClassificationLevel.INTERNAL, "差旅报销流程");

    Set<UUID> before = idsOf(service.preview(app.id(), created.token(), OWNER));
    assertThat(before).as("第一次访问应当看得见两条").containsExactlyInAnyOrder(first, second);

    // 两次访问之间把内容升密（两条都抬到冻结值之上）
    segments.put(first, ClassificationLevel.CONFIDENTIAL, "差旅住宿标准");
    segments.put(second, ClassificationLevel.CONFIDENTIAL, "差旅报销流程");

    Set<UUID> after = idsOf(service.preview(app.id(), created.token(), OWNER));

    assertThat(after)
        .as("★ 同一条 token 第二次访问的可见集合必须收窄 ⇒ 证明它每次都用冻结值重新过滤了一遍，" + "而不是复用创建时算出来的结果（复用的话这里仍然会看到两条）")
        .isEmpty();
    assertThat(after.size())
        .as("收窄：%s → %s", before.size(), after.size())
        .isLessThan(before.size());
  }

  // ────────────────────────── 列表与归还形状 ──────────────────────────

  /** 列表返回该应用下的全部凭证（含已撤销的 —— 契约有 {@code revokedAt}，说明它要能被看见）。 */
  @Test
  void listReturnsRevokedOnesToo() {
    Assistant app = app();
    AccessCredentialView alive = service.create(app.id(), null, OWNER);
    AccessCredentialView dead = service.create(app.id(), null, OWNER);
    service.revoke(app.id(), dead.id(), OWNER);

    assertThat(service.list(app.id(), OWNER))
        .extracting(AccessCredentialView::id)
        .containsExactlyInAnyOrder(alive.id(), dead.id());
  }

  /** 看不见的应用 ⇒ 404（不是 403），且**连带**它的凭证也看不见。 */
  @Test
  void invisibleAppHidesItsCredentials() {
    Assistant app = app();
    service.create(app.id(), null, OWNER);
    accessScope.setVisible(false);

    assertThatThrownBy(() -> service.list(app.id(), OWNER))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  /** 同一个应用可以有两条凭证，且 token 不重复（可单条吊销、可多端同时存在）。 */
  @Test
  void tokenIsUniqueAcrossCredentials() {
    Assistant app = app();
    AccessCredentialView first = service.create(app.id(), null, OWNER);
    AccessCredentialView second = service.create(app.id(), null, OWNER);

    assertThat(first.token()).isNotEqualTo(second.token());
    assertThat(first.token()).as("token 是 URL 安全的公开串（会出现在链接里）").startsWith("pk_live_");
    assertThat(first.token()).matches("[A-Za-z0-9_\\-]+");
  }

  /** 列表接口要返回 token ⇒ MVP 存可取回的值（取舍写在实体 javadoc 里）。 */
  @Test
  void tokenIsRetrievableForTheListEndpoint() {
    Assistant app = app();
    AccessCredentialView created = service.create(app.id(), null, OWNER);

    assertThat(service.list(app.id(), OWNER))
        .singleElement()
        .extracting(AccessCredentialView::token)
        .isEqualTo(created.token());
  }

  // ────────────────────────── 假实现 ──────────────────────────

  private static final class FakeAssistants implements AssistantRepository {

    private final Map<UUID, Assistant> store = new LinkedHashMap<>();

    @Override
    public Assistant save(Assistant assistant) {
      store.put(assistant.id(), assistant);
      return assistant;
    }

    @Override
    public Optional<Assistant> findById(UUID id) {
      return Optional.ofNullable(store.get(id)).filter(a -> !a.isDeleted());
    }

    @Override
    public Optional<Assistant> findByClientRequestIdAndOwnerId(String key, UUID ownerId) {
      return Optional.empty();
    }

    @Override
    public List<Assistant> search(ScopeFilter scope, Filter filter, int offset, int limit) {
      return List.of();
    }

    @Override
    public long countSearch(ScopeFilter scope, Filter filter) {
      return 0;
    }
  }

  private static final class FakeCredentials implements AccessCredentialRepository {

    private final Map<UUID, AccessCredential> store = new LinkedHashMap<>();

    @Override
    public AccessCredential save(AccessCredential credential) {
      store.put(credential.id(), credential);
      return credential;
    }

    @Override
    public Optional<AccessCredential> findById(UUID id) {
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<AccessCredential> findByToken(String token) {
      return store.values().stream().filter(c -> c.token().equals(token)).findFirst();
    }

    @Override
    public List<AccessCredential> findByAssistantId(UUID assistantId) {
      return store.values().stream().filter(c -> c.assistantId().equals(assistantId)).toList();
    }
  }

  /**
   * 假检索端口：只回答"<strong>在这个密级上限下</strong>哪些分段可见"。
   *
   * <p>★ 口径与真实现一致（{@code JdbcSegmentSearchQuery} 的 {@code s.classification_rank <= ?}）——
   * 用"不按密级过滤"的假实现会让判据 ②③ 变成永远绿。 真 SQL 由 {@code AccessCredentialFilteringTest} 验。
   */
  private static final class FakeSegments implements SegmentSearchPort {

    private final Map<UUID, ClassificationLevel> levels = new LinkedHashMap<>();

    void put(UUID segmentId, ClassificationLevel level, String text) {
      levels.put(segmentId, level);
    }

    @Override
    public List<Scored> visibleSegments(
        Collection<UUID> kbIds, ClassificationLevel ceiling, int limit) {
      if (ceiling == null || kbIds == null || kbIds.isEmpty()) {
        return List.of();
      }
      return levels.entrySet().stream()
          .filter(e -> e.getValue().within(ceiling))
          .limit(limit)
          .map(e -> new Scored(e.getKey(), 1.0))
          .toList();
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.copyOf(requested);
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本测试不用向量路");
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      throw new UnsupportedOperationException("本测试走 visibleSegments，不走全文路");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("本测试不用向量路");
    }

    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, Collection<UUID> kbIds) {
      return Set.of();
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      return List.of();
    }
  }
}
