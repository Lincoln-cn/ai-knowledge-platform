package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantFavoriteRepository;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.AssistantType;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ModelStatusPort;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用配置的服务层（第 15 周 · 0.4.3）。
 *
 * <h3>★ 判据① 发布校验：为什么校验在服务层而不在实体</h3>
 *
 * <p>AS-I2（模型已加载）要读 {@code modelcatalog}、AS-I3（知识库可用）要读 {@code knowledge} —— 实体不该知道别的模块。
 * 实体只负责"状态怎么变"（{@code publish(boolean)}），"能不能发布"由这里判； 而<strong>校验不过时状态一个字节都不变</strong>（{@code
 * publish} 在 {@code checkBeforePublish} 之后才被调用）。
 *
 * <h3>★ 复用而不是新写</h3>
 *
 * <ul>
 *   <li>AS-I3 用 <strong>{@link SegmentSearchPort#visibleKbIds}</strong>（它已经含"启用 + 未删 + 可见"三条判据，
 *       {@code JdbcSegmentSearchQuery:139}）—— 不新开一条"这个库能不能用"的判定，否则同一件事会有第二处实现
 *   <li>AS-I2 用 {@link ModelStatusPort}（新增的平台端口；{@code pickChatModel()} 回答的是"给我一个能用的"，
 *       答不了"我绑定的那个现在能用吗"）
 *   <li>可见性用 {@link AccessScopePort#canSee}（判定只有一处实现，{@code 07 §3.3}）
 * </ul>
 *
 * <h3>⚠️ 本批明确<strong>不做</strong>的（都在别的批次/模块，且都在响应里如实体现）</h3>
 *
 * <ul>
 *   <li><b>列表与数据范围过滤</b>：已落 —— 范围谓词与筛选（来源分段 / 排序 / 标签 / 关键词 / 只看收藏）合进同一条 SQL（{@code
 *       AssistantRepositoryAdapter}），{@code total} 与页内条数同口径
 *   <li><b>编排应用</b>：{@code workflow} 模块只有一个 {@code package-info}（编排执行引擎属 V1.0）⇒
 *       编排应用<strong>无法发布</strong>，且发布校验里那两项<strong>如实失败</strong>（见 {@link #checkBeforePublish}）
 *   <li><b>提示词模板</b>：{@code prompt-configs} 那一域尚未建表 ⇒ 导出包里它的依赖项标 {@code satisfied=false}
 * </ul>
 */
@Service
public class AssistantService {

  private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

  /**
   * 导出包格式标识。★ 与 mock 逐字一致（{@code frontend/src/mock/handlers/assistants.ts} 的 {@code
   * 'assistant-bundle@1'}）。
   */
  public static final String BUNDLE_FORMAT = "assistant-bundle@1";

  /** AS-I1 / AS-I5 的区间（{@code 02 §273-277} + {@code 10} 册对 §277 的扩充）。 */
  private static final double THRESHOLD_MIN = 0.0;

  private static final double THRESHOLD_MAX = 1.0;
  private static final int TOP_N_MIN = 1;
  private static final int TOP_N_MAX = 50;
  private static final int QUERY_COUNT_MIN = 0;
  private static final int QUERY_COUNT_MAX = 5;
  private static final int MAX_SUB_QUESTIONS_MIN = 1;
  private static final int MAX_SUB_QUESTIONS_MAX = 5;
  private static final int NAME_MAX = 30;

  /** 分页口径：契约默认 20 条一页，单页硬顶 100（与知识库同一条纪律：不许无界分页）。 */
  private static final int DEFAULT_PAGE_SIZE = 20;

  private static final int MAX_PAGE_SIZE = 100;

  /** 契约 {@code AssistantQuery} 的枚举词汇（收了就按它判，不收就等于"看起来在筛、实际没筛"）。 */
  private static final Set<String> ORIGINS = Set.of("OFFICIAL", "SHARED", "MINE");

  private static final Set<String> SORTS = Set.of("POPULAR", "LATEST", "NAME");

  private final AssistantRepository assistants;
  private final AssistantFavoriteRepository favorites;
  private final AccessScopePort accessScope;
  private final ModelStatusPort modelStatus;
  private final SegmentSearchPort segmentSearch;

  public AssistantService(
      AssistantRepository assistants,
      AssistantFavoriteRepository favorites,
      AccessScopePort accessScope,
      ModelStatusPort modelStatus,
      SegmentSearchPort segmentSearch) {
    this.assistants = assistants;
    this.favorites = favorites;
    this.accessScope = accessScope;
    this.modelStatus = modelStatus;
    this.segmentSearch = segmentSearch;
  }

  // ────────────────────────── 创建 / 读取 / 更新 ──────────────────────────

  /**
   * 新建应用。
   *
   * <p>★ 幂等：带 {@code clientRequestId} 时，同一 {@code (clientRequestId, owner)}
   * 重复创建<strong>返回既有应用</strong>， 而不是"名称已存在"。与知识库同一条口径（{@code V12}）：建应用成功但后续配置失败后，重试不该被卡死。
   *
   * @param ownerId 由 Web 层从 JWT 的 {@code sub} 取出 —— <strong>不接受前端传</strong>（否则能把应用挂到别人名下）
   */
  @Transactional
  public AssistantView create(Draft draft, UUID ownerId) {
    String idempotencyKey = blankToNull(draft.clientRequestId());
    if (idempotencyKey != null) {
      Optional<Assistant> existing =
          assistants.findByClientRequestIdAndOwnerId(idempotencyKey, ownerId);
      if (existing.isPresent()) {
        log.info("创建应用命中幂等键 {}，返回既有应用 {}", idempotencyKey, existing.get().id());
        return view(existing.get());
      }
    }

    // ★ 先补全成"完整入参"（缺的用默认值），再走与更新同一条写入路径 ——
    //   两条路径共用 applyCommon，字段集不会分叉
    Draft full = complete(draft, null);
    Assistant assistant =
        new Assistant(
            UUID.randomUUID(),
            requireName(full.name()),
            full.icon(),
            full.description(),
            requireType(full.type()),
            full.modelId(),
            full.rolePrompt(),
            ownerId,
            ownerId);
    applyCommon(assistant, full, ownerId);
    assistant.recordClientRequestId(idempotencyKey);
    return view(assistants.save(assistant));
  }

  /** 取一个应用。★ 看不见的应用返回 <strong>404</strong> 而不是 403（{@code 08 §4.4.3} 纪律 2）。 */
  @Transactional(readOnly = true)
  public AssistantView get(UUID id) {
    return view(requireVisible(id));
  }

  /**
   * 列表（契约 {@code listAssistants} / {@code AssistantQuery}）。
   *
   * <p>★ 范围与筛选<strong>拼进查询</strong>（仓储那侧共用一份谓词），{@code total} 与页内条数同口径。
   *
   * <p>★ {@code favorite=true} 走<strong>相关 EXISTS 子查询</strong>（{@code
   * AssistantRepositoryAdapter}），而不是"把收藏全拉进内存再拼 IN" —— 后者会让 {@code total} 变成另一个口径。 {@code
   * favorited} 字段用一次批量 IN 查询装饰，保证"列表里点过心的那条"与"详情页"读数一致。
   */
  @Transactional(readOnly = true)
  public PageResult<AssistantView> list(
      String type,
      Boolean published,
      Boolean mine,
      String origin,
      Boolean favorite,
      String tag,
      String keyword,
      String sort,
      Integer page,
      Integer size) {
    int safeSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    AssistantRepository.Filter filter =
        new AssistantRepository.Filter(
            parseTypeOrNull(type),
            published,
            mine != null && mine,
            requireIn("来源分段", origin, ORIGINS),
            blankToNull(tag),
            blankToNull(keyword),
            requireIn("排序", sort, SORTS),
            favorite != null && favorite);

    ScopeFilter scope = accessScope.currentScope();
    List<Assistant> found = assistants.search(scope, filter, (safePage - 1) * safeSize, safeSize);
    long total = assistants.countSearch(scope, filter);

    // ★ 一次 IN 查询回答"这一页里哪几个被我收藏"：逐条 exists 是 N+1，而空页在适配器那层直接返回空集
    Set<UUID> favoritedIds =
        scope.me() == null
            ? Set.of()
            : favorites.favoritedAmong(scope.me(), found.stream().map(Assistant::id).toList());
    return PageResult.of(
        found.stream()
            .map(assistant -> view(assistant, favoritedIds.contains(assistant.id())))
            .toList(),
        total,
        safePage,
        safeSize);
  }

  /**
   * 全部可见标签（契约 {@code listAssistantTags}，{@code #195} 的标签子项；市场筛选下拉框在用）。
   *
   * <p>★ 与 {@link #list} 同一份范围口径：标签只从<strong>当前请求者看得见的应用</strong>里长出来 —— 全表 {@code distinct}
   * 会把别人私有应用上的标签放进市场下拉框，那是看不见的数据从侧面漏出去。
   *
   * <p>★ 不按 {@code published} 过滤：与 mock（{@code frontend/src/mock/handlers/assistants.ts}）一致 ——
   * 标签是"市场里有哪些主题"的索引，草稿应用只要我看得见，它的标签就可以出现在筛选里。
   */
  @Transactional(readOnly = true)
  public List<String> listTags() {
    return assistants.listTags(accessScope.currentScope());
  }

  /**
   * 收藏 / 取消收藏（契约 {@code favoriteAssistant} / {@code unfavoriteAssistant}，{@code #195} 的收藏子项）。
   *
   * <p>★ 两条判据：① 只有<strong>看得见</strong>的应用能收藏（看不见 ⇒ 404，与 {@code get} 同一口径，不泄露 id 是否存在）；② 幂等 —— 重复收藏
   * / 重复取消都返回成功，页面才敢做乐观更新。
   *
   * <p>★ 为什么不校验 {@code assistant:edit}：收藏是<strong>读者</strong>的动作（市场页任何能看见它的人都能点心形）， 不是配置写。权限点由 Web
   * 层的 {@code assistant:view} 管。
   */
  @Transactional
  public AssistantView setFavorite(UUID id, boolean favorited, UUID userId) {
    Assistant assistant = requireVisible(id);
    if (favorited) {
      favorites.add(id, userId);
    } else {
      favorites.remove(id, userId);
    }
    return view(assistant, favorited);
  }

  /**
   * 删除（软删，{@code 02 §5.1}）。
   *
   * <p>★ 内置应用不可删：它是平台的示范应用（{@code builtin}），删掉之后"应用市场"会缺一块。
   */
  @Transactional
  public void delete(UUID id, UUID actorId) {
    Assistant assistant = requireVisible(id);
    if (assistant.builtin()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "内置应用不可删除", "内置应用是平台的示范应用；要移除请找管理员");
    }
    assistant.markDeleted(actorId);
    assistants.save(assistant);
  }

  /**
   * 改配置。
   *
   * <p>★ <strong>{@code type} 改不了</strong>（契约 {@code UpsertAssistantBody} 的注释："更新时后端应忽略该字段"）：
   * 这里连"忽略"都谈不上 —— {@link Assistant#applyConfig} 的入参里没有 type， 所以 {@code draft.type()} 在更新路径上
   * <strong>不被读取</strong>（不是"读了再丢掉"）。
   */
  @Transactional
  public AssistantView update(UUID id, Draft draft, UUID editorId) {
    Assistant assistant = requireVisible(id);
    if (assistant.builtin()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "内置应用不可修改", "内置应用仅供问答；要改配置请先复制一份再改");
    }
    // ★ 契约里 PUT 是 Partial<UpsertAssistantBody> ⇒ 没传的字段**保持原值**（不是清空）
    applyCommon(assistant, complete(draft, assistant), editorId);
    return view(assistants.save(assistant));
  }

  // ────────────────────────── 发布校验与发布（判据 ①）──────────────────────────

  /**
   * 发布前校验（契约 {@code checkAssistantBeforePublish}）。
   *
   * <p>★ 校验项与 mock <strong>逐字同口径</strong>（key 与 label）：{@code model} / {@code knowledge} / {@code
   * rolePrompt} / {@code retrieval} / {@code prologue}；编排应用走 {@code node-models} / {@code
   * workflow}。 三方（契约类型 · mock 口径 · 后端实现）不一致时，界面显示的和后端判的不是同一件事。
   *
   * <p>★ {@code detail} 一律写成"为什么 + 怎么改"：校验的意义就是让错误<strong>暴露在配置者面前</strong>， 而不是等他发布之后由使用者撞上（{@code
   * 02 §279} 的原话）。
   */
  @Transactional(readOnly = true)
  public PublishCheckResult checkBeforePublish(UUID id) {
    Assistant assistant = requireVisible(id);
    List<PublishCheckResult.Item> items = new ArrayList<>();

    if (assistant.type() == AssistantType.WORKFLOW) {
      // ★ 如实失败，而不是"跳过去"：画布后端（workflow 模块）尚未落地，节点引用的模型与流程形状都读不到。
      //   让它们通过等于对外承诺"这条流程能跑"，而那是编造。
      items.add(
          PublishCheckResult.Item.fail(
              "node-models", "流程内的模型均已加载", "画布后端（workflow 模块）尚未落地，读不到节点引用的模型"));
      items.add(PublishCheckResult.Item.fail("workflow", "流程已配置", "尚未绘制流程：画布后端未落地，编排应用本批无法发布"));
      return PublishCheckResult.of(items);
    }

    items.add(modelItem(assistant.modelId()));
    items.add(knowledgeItem(assistant.kbIds()));
    items.add(
        assistant.rolePrompt().isBlank()
            ? PublishCheckResult.Item.fail(
                "rolePrompt", "角色设定已填写", "角色设定为空，回答风格不受控。请填写角色设定或选用一条提示词")
            : PublishCheckResult.Item.pass("rolePrompt", "角色设定已填写"));
    items.add(retrievalItem(assistant));
    items.add(
        assistant.prologue().isBlank()
            ? PublishCheckResult.Item.fail("prologue", "开场白已填写", "开场白为空，公开页打开后没有引导语。建议填写一句开场白")
            : PublishCheckResult.Item.pass("prologue", "开场白已填写"));
    return PublishCheckResult.of(items);
  }

  /**
   * 发布 / 取消发布。
   *
   * <p>★ 发布必须先过校验（{@code 02 §288}：{@code publish()} 执行 AS-I2/I3/I4/I5 全部校验）。 校验不过 ⇒ {@link
   * ErrorCode#PUBLISH_CHECK_FAILED}（6001，契约里已有这个码），而<strong>应用状态一个字节都不变</strong>—— "发布一半"是不存在的状态。
   *
   * <p>★ 取消发布不校验：下线一个应用不该被配置校验拦住（那是"能不能对外服务"的问题，不是"能不能下线"）。
   */
  @Transactional
  public AssistantView publish(UUID id, boolean published, UUID actorId) {
    Assistant assistant = requireVisible(id);
    if (published) {
      PublishCheckResult check = checkBeforePublish(id);
      if (!check.passed()) {
        throw new BusinessException(
            ErrorCode.PUBLISH_CHECK_FAILED,
            "发布前校验未通过：" + check.failureSummary(),
            "按上面每一项的说明改完再发布（校验不过时应用状态不变）");
      }
    }
    assistant.publish(published, actorId);
    return view(assistants.save(assistant));
  }

  // ────────────────────────── 导出（判据 ③）──────────────────────────

  /**
   * 导出 JSON 包（契约 {@code exportAssistant}）。
   *
   * <p>★ {@code includesDocuments} 当前<strong>只回显，不影响包内容</strong>：带上文档正文要先做文档与分段的打包格式
   * （那是导入导出那一批的事），本批把开关收进契约与响应，但不假装已经带上了 —— 响应里 {@code includesDocuments} 如实回显请求值，而包内容只有配置与依赖清单。
   *
   * <p>⚠️ {@code config.workflow} <strong>不下发</strong>：流程定义住在 {@code workflow} 模块，而它尚未落地。
   * 回一个空图会让导入方以为"这条流程本来就是空的"；依赖清单里那条 {@code WORKFLOW} 项会如实说明后果。
   */
  @Transactional(readOnly = true)
  public AssistantBundleView export(UUID id, boolean includeDocuments) {
    Assistant assistant = requireVisible(id);
    return new AssistantBundleView(
        BUNDLE_FORMAT,
        Instant.now(),
        includeDocuments,
        new AssistantBundleView.Config(
            assistant.name(),
            assistant.icon(),
            assistant.description(),
            assistant.type().name(),
            assistant.modelId(),
            assistant.kbIds(),
            assistant.rolePrompt(),
            assistant.promptIds(),
            retrievalView(assistant),
            assistant.multiTurn(),
            assistant.thinkingEnabled(),
            assistant.prologue(),
            assistant.quickQuestions()),
        dependenciesOf(assistant));
  }

  // ────────────────────────── 内部 ──────────────────────────

  private Assistant requireVisible(UUID id) {
    Assistant assistant =
        assistants
            .findById(id)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "应用不存在", "它可能已被删除，请刷新列表后重试"));
    if (!accessScope.canSee(assistant)) {
      // ★ 看不见的资源返回 404 而不是 403（08 §4.4.3 纪律 2）：403 等于告诉对方"这个 id 存在"
      throw new BusinessException(ErrorCode.NOT_FOUND, "应用不存在", "它可能已被删除，或者不在你的可见范围内");
    }
    return assistant;
  }

  /** 组装对外形状（单条）。★ 收藏状态查一次 {@code (id, me)}；列表走批量重载，避免 N+1。 */
  private AssistantView view(Assistant assistant) {
    UUID me = accessScope.currentScope().me();
    return view(assistant, me != null && favorites.exists(assistant.id(), me));
  }

  /** 组装对外形状，收藏状态由调用方给出（列表已批量查好）。 */
  private AssistantView view(Assistant assistant, boolean favorited) {
    String modelName =
        assistant.modelId() == null
            ? null
            : modelStatus
                .find(assistant.modelId())
                .map(ModelStatusPort.ModelSnapshot::name)
                .orElse(null);
    return AssistantView.from(assistant, modelName, favorited);
  }

  /** 写入共用部分（创建与更新都走这里，且入参已经由 {@link #complete} 补全 —— 这里不再判 null）。 */
  private void applyCommon(Assistant assistant, Draft draft, UUID actorId) {
    RetrievalDraft retrieval = draft.retrieval();
    validateRetrieval(retrieval);
    assistant.applyConfig(
        requireName(draft.name()),
        draft.icon(),
        draft.description(),
        draft.modelId(),
        draft.rolePrompt(),
        draft.multiTurn(),
        draft.thinkingEnabled(),
        draft.prologue(),
        draft.quickQuestions(),
        draft.tags(),
        actorId);
    assistant.applyRetrieval(
        RetrievalMode.parse(retrieval.mode()),
        BigDecimal.valueOf(retrieval.threshold()),
        retrieval.topN(),
        NoRefStrategy.parse(retrieval.noRefStrategy()),
        retrieval.queryCount(),
        retrieval.decomposeEnabled(),
        retrieval.maxSubQuestions(),
        actorId);
    assistant.bind(draft.kbIds(), draft.promptIds(), actorId);
  }

  /**
   * 把请求体补成一份<strong>完整入参</strong>：创建时缺的用默认值，更新时缺的用当前值。
   *
   * <p>★ 为什么要有这一步：契约里 PUT 是 {@code Partial<UpsertAssistantBody>}（只发改动的字段）， 而实体侧是"覆盖式"写入（{@code
   * applyConfig} 覆盖全部）。两者的差异必须在一处收口 —— 散在实体里判 null， 会让"没传"与"明确传了空"再也分不开（前者该保持原值，后者该真的清空）。
   *
   * @param current 更新时传当前实体；创建时传 {@code null}
   */
  private static Draft complete(Draft draft, Assistant current) {
    boolean creating = current == null;
    return new Draft(
        draft.name() != null ? draft.name() : (creating ? null : current.name()),
        draft.icon() != null ? draft.icon() : (creating ? "" : current.icon()),
        draft.description() != null
            ? draft.description()
            : (creating ? null : current.description()),
        // ★ type 只在创建时被读取：更新路径写回 current.type()（创建后不可改）
        creating ? draft.type() : current.type(),
        draft.modelId() != null ? draft.modelId() : (creating ? null : current.modelId()),
        draft.rolePrompt() != null ? draft.rolePrompt() : (creating ? "" : current.rolePrompt()),
        draft.kbIds() != null ? draft.kbIds() : (creating ? List.of() : current.kbIds()),
        draft.promptIds() != null
            ? draft.promptIds()
            : (creating ? List.of() : current.promptIds()),
        draft.retrieval() != null
            ? draft.retrieval()
            : (creating ? RetrievalDraft.defaults() : retrievalOf(current)),
        draft.multiTurn() != null ? draft.multiTurn() : (creating || current.multiTurn()),
        draft.thinkingEnabled() != null
            ? draft.thinkingEnabled()
            : (!creating && current.thinkingEnabled()),
        draft.prologue() != null ? draft.prologue() : (creating ? "" : current.prologue()),
        draft.quickQuestions() != null
            ? draft.quickQuestions()
            : (creating ? List.of() : current.quickQuestions()),
        draft.tags() != null ? draft.tags() : (creating ? List.of() : current.tags()),
        draft.clientRequestId());
  }

  private static RetrievalDraft retrievalOf(Assistant assistant) {
    return new RetrievalDraft(
        assistant.retrievalMode().name(),
        assistant.retrievalThreshold().doubleValue(),
        assistant.retrievalTopN(),
        assistant.retrievalNoRefStrategy().name(),
        assistant.retrievalQueryCount(),
        assistant.retrievalDecomposeEnabled(),
        assistant.retrievalMaxSubQuestions());
  }

  private PublishCheckResult.Item modelItem(UUID modelId) {
    if (modelId == null) {
      return PublishCheckResult.Item.fail("model", "对话模型已加载", "未选择对话模型。请选择一个「已加载」的对话模型");
    }
    Optional<ModelStatusPort.ModelSnapshot> found = modelStatus.find(modelId);
    if (found.isEmpty()) {
      return PublishCheckResult.Item.fail("model", "对话模型已加载", "绑定的对话模型不存在（可能已被删除）。请重新选择一个「已加载」的模型");
    }
    if (!found.get().loaded()) {
      return PublishCheckResult.Item.fail(
          "model", "对话模型已加载", "模型「" + found.get().name() + "」当前未加载完成，请先在模型管理中加载完成");
    }
    return PublishCheckResult.Item.pass("model", "对话模型已加载");
  }

  /** AS-I3：用检索端口那套判据（启用 + 未删 + 可见），不新写一份。 */
  private PublishCheckResult.Item knowledgeItem(List<UUID> kbIds) {
    if (kbIds.isEmpty()) {
      return PublishCheckResult.Item.pass("knowledge", "知识库均可用");
    }
    Set<UUID> usable = new LinkedHashSet<>(segmentSearch.visibleKbIds(kbIds));
    long unavailable = kbIds.stream().filter(id -> !usable.contains(id)).count();
    return unavailable == 0
        ? PublishCheckResult.Item.pass("knowledge", "知识库均可用")
        : PublishCheckResult.Item.fail(
            "knowledge",
            "知识库均可用",
            "绑定的知识库里有 " + unavailable + " 个不可用（已停用、已删除，或不在你的可见范围内），它们不会参与检索。请启用或移除绑定");
  }

  private PublishCheckResult.Item retrievalItem(Assistant assistant) {
    String outOfRange = outOfRangeReason(assistant);
    return outOfRange == null
        ? PublishCheckResult.Item.pass("retrieval", "检索参数合法")
        : PublishCheckResult.Item.fail("retrieval", "检索参数合法", outOfRange);
  }

  /**
   * AS-I5：读的是<strong>存下来的值</strong>，不是请求体。
   *
   * <p>写入路径已经校验过一遍（{@link #validateRetrieval}），这一项是第二道 —— 它防的是"存量数据"与"将来新增的写入口" （例如批量导入直接写库）。★
   * 两道并存不是冗余：请求校验管"这次别写坏"，发布校验管"现在这份能不能用"。
   */
  private static String outOfRangeReason(Assistant assistant) {
    List<String> bad = new ArrayList<>();
    double threshold = assistant.retrievalThreshold().doubleValue();
    if (threshold < THRESHOLD_MIN || threshold > THRESHOLD_MAX) {
      bad.add("相似度阈值 " + threshold + " 不在 0–1 之间");
    }
    int topN = assistant.retrievalTopN();
    if (topN < TOP_N_MIN || topN > TOP_N_MAX) {
      bad.add("TopN " + topN + " 不在 1–50 之间");
    }
    int queryCount = assistant.retrievalQueryCount();
    if (queryCount < QUERY_COUNT_MIN || queryCount > QUERY_COUNT_MAX) {
      bad.add("查询理解条数 " + queryCount + " 不在 0–5 之间");
    }
    int maxSub = assistant.retrievalMaxSubQuestions();
    if (maxSub < MAX_SUB_QUESTIONS_MIN || maxSub > MAX_SUB_QUESTIONS_MAX) {
      bad.add("子问题上限 " + maxSub + " 不在 1–5 之间");
    }
    return bad.isEmpty() ? null : String.join("；", bad) + "。请改回合法区间再发布";
  }

  private static void validateRetrieval(RetrievalDraft retrieval) {
    String reason = null;
    if (retrieval.threshold() < THRESHOLD_MIN || retrieval.threshold() > THRESHOLD_MAX) {
      reason = "相似度阈值应在 0–1 之间";
    } else if (retrieval.topN() < TOP_N_MIN || retrieval.topN() > TOP_N_MAX) {
      reason = "TopN 应在 1–50 之间";
    } else if (retrieval.queryCount() < QUERY_COUNT_MIN
        || retrieval.queryCount() > QUERY_COUNT_MAX) {
      reason = "查询理解条数应在 0–5 之间";
    } else if (retrieval.maxSubQuestions() < MAX_SUB_QUESTIONS_MIN
        || retrieval.maxSubQuestions() > MAX_SUB_QUESTIONS_MAX) {
      reason = "子问题上限应在 1–5 之间";
    }
    if (reason != null) {
      // ★ 不静默夹紧：收下越界值再按别的值跑，用户看到的与生效的是两回事
      throw new BusinessException(ErrorCode.BAD_REQUEST, reason, "请改成合法区间的值后重试");
    }
  }

  private List<AssistantBundleView.Dependency> dependenciesOf(Assistant assistant) {
    List<AssistantBundleView.Dependency> deps = new ArrayList<>();
    if (assistant.modelId() != null) {
      Optional<ModelStatusPort.ModelSnapshot> model = modelStatus.find(assistant.modelId());
      boolean loaded = model.map(ModelStatusPort.ModelSnapshot::loaded).orElse(false);
      deps.add(
          new AssistantBundleView.Dependency(
              "MODEL",
              model
                  .map(ModelStatusPort.ModelSnapshot::name)
                  .orElse("（模型 " + assistant.modelId() + "）"),
              loaded ? "已加载" : "未加载或已不存在",
              loaded,
              loaded ? null : "导入方需要先加载它；否则这个应用无法回答"));
    }
    if (!assistant.kbIds().isEmpty()) {
      Set<UUID> usable = new LinkedHashSet<>(segmentSearch.visibleKbIds(assistant.kbIds()));
      for (UUID kbId : assistant.kbIds()) {
        boolean ok = usable.contains(kbId);
        deps.add(
            new AssistantBundleView.Dependency(
                "KNOWLEDGE_BASE",
                // ★ 只有 id：检索端口不提供库名，编一个名字比给出 id 更坏
                "知识库 " + kbId,
                ok ? "可用" : "不可用（已停用 / 已删除 / 不可见）",
                ok,
                ok ? null : "导入后这一段知识不会被检索到，需要启用或重新绑定"));
      }
    }
    for (UUID promptId : assistant.promptIds()) {
      deps.add(
          new AssistantBundleView.Dependency(
              "PROMPT", "提示词 " + promptId, "提示词配置尚未落地，无法校验", false, "导入后需要手动重新绑定提示词"));
    }
    if (assistant.type() == AssistantType.WORKFLOW) {
      deps.add(
          new AssistantBundleView.Dependency(
              "WORKFLOW", "流程定义", "画布后端（workflow 模块）未落地，无法导出", false, "导入后画布为空，需要重新绘制流程"));
    }
    return deps;
  }

  private static AssistantView.RetrievalView retrievalView(Assistant assistant) {
    return new AssistantView.RetrievalView(
        assistant.retrievalMode().name(),
        assistant.retrievalThreshold().doubleValue(),
        assistant.retrievalTopN(),
        assistant.retrievalNoRefStrategy().name(),
        assistant.retrievalQueryCount(),
        assistant.retrievalDecomposeEnabled(),
        assistant.retrievalMaxSubQuestions());
  }

  private static AssistantType requireType(AssistantType type) {
    if (type == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "应用类型必填（SIMPLE / WORKFLOW）", "创建时必须选定类型，之后不可更改");
    }
    return type;
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "应用名称必填", "名称 1–30 字");
    }
    String trimmed = name.trim();
    if (trimmed.length() > NAME_MAX) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "应用名称不能超过 " + NAME_MAX + " 字", "当前 " + trimmed.length() + " 字");
    }
    return trimmed;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** 列表筛选用：类型拼错 ⇒ 可读 400（而不是交给 Jackson/枚举抛一句开发者的话）。 */
  private static AssistantType parseTypeOrNull(String raw) {
    String value = blankToNull(raw);
    if (value == null) {
      return null;
    }
    try {
      return AssistantType.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "应用类型只支持 SIMPLE / WORKFLOW，收到：" + raw, "请检查查询参数");
    }
  }

  /** 枚举型查询参数的统一校验：不在词汇表里 ⇒ 可读 400（★ 不许"收下但不筛"）。 */
  private static String requireIn(String field, String value, Set<String> allowed) {
    String trimmed = blankToNull(value);
    if (trimmed == null) {
      return null;
    }
    String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
    if (!allowed.contains(upper)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          field + "只支持 " + String.join(" / ", allowed) + "，收到：" + value,
          "请检查查询参数");
    }
    return upper;
  }

  /**
   * 创建 / 更新的入参。
   *
   * <p>★ 为什么不直接收 Web 层的请求体：请求体是契约形状（字段可空、字符串表枚举），这里是领域口径（枚举已解析、 区间已校验）。这一层翻译留在 Web
   * 层，服务层收到的就是"已经说得清楚"的东西。
   *
   * @param type 只在创建时被读取；更新路径<strong>不读它</strong>（创建后不可改）
   * @param multiTurn / {@code thinkingEnabled} 用包装类型：契约里 PUT 是部分更新，{@code null} 表示"没传、保持原值" ——
   *     用基本类型就再也分不开"没传"与"传了 false"
   */
  public record Draft(
      String name,
      String icon,
      String description,
      AssistantType type,
      UUID modelId,
      String rolePrompt,
      List<UUID> kbIds,
      List<UUID> promptIds,
      RetrievalDraft retrieval,
      Boolean multiTurn,
      Boolean thinkingEnabled,
      String prologue,
      List<String> quickQuestions,
      List<String> tags,
      String clientRequestId) {}

  /**
   * 检索参数（契约 {@code RetrievalParams}）。
   *
   * <p>★ 缺省值与知识库那侧一致：{@code HYBRID / 0.6 / 5 / REFUSE / 3 / true / 3}。
   */
  public record RetrievalDraft(
      String mode,
      double threshold,
      int topN,
      String noRefStrategy,
      int queryCount,
      boolean decomposeEnabled,
      int maxSubQuestions) {

    public static RetrievalDraft defaults() {
      return new RetrievalDraft("HYBRID", 0.6, 5, "REFUSE", 3, true, 3);
    }
  }
}
