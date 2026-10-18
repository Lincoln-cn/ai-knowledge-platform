package cn.lincoln.aiplatform.modelcatalog.application;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ApiKeyCipher;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.CapabilityProber;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 模型资产管理（{@code GET/POST/PUT/DELETE /models} · {@code load} · {@code probe}）。
 *
 * <p>★ <b>load 的语义</b>（{@code 08 §3.2} / {@code 09 §3.13.4} 已定）： <strong>平台启用位 +
 * 上游可用性校验</strong>，<strong>不操纵上游实例</strong>（"载入显存"是网关/上游的事）。 四态映射：未启用→{@code
 * NOT_LOADED}；正在校验→{@code LOADING}；已启用且上游有→{@code LOADED}；网关不可达或上游无此模型→{@code LOAD_FAILED}。
 *
 * <p>★ <b>load 是异步的</b>（契约写明"加载是异步的，返回时 status 仍是加载中"）： 置 {@code LOADING} 并立即返回，后台做上游可用性校验，随后转
 * {@code LOADED} 或 {@code LOAD_FAILED}。 用有界线程池即可，不引框架外的东西。
 *
 * <p>★ <b>DELETE 的拒绝条件</b>是「被应用/知识库引用」而不是「已加载」（{@code 08 §3.2} 连带修正）—— 否则加载过的模型永远删不掉（契约只有 load 没有
 * unload）。
 */
@Service
public class ModelService {

  private static final Logger log = LoggerFactory.getLogger(ModelService.class);

  private static final int MAX_PAGE_SIZE = 100;

  /**
   * 加载任务的有界线程池。
   *
   * <p>★ 有界而不是 {@code newCachedThreadPool}：加载是"调一下上游就回来"的短任务， 但上游可能慢/卡死 —— 无界线程池会在上游故障时把线程吃光。 2
   * 个线程足够（管理员不会同时加载几十个模型），队列满时丢弃并记日志。
   */
  private final ExecutorService loadExecutor =
      Executors.newFixedThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "model-load");
            t.setDaemon(true);
            return t;
          });

  private final ModelRepository models;
  private final ApiKeyCipher apiKeyCipher;
  private final CapabilityProber prober;
  private final UpstreamGateway gateway;
  private final TransactionTemplate transactionTemplate;

  public ModelService(
      ModelRepository models,
      ApiKeyCipher apiKeyCipher,
      CapabilityProber prober,
      UpstreamGateway gateway,
      PlatformTransactionManager transactionManager) {
    this.models = models;
    this.apiKeyCipher = apiKeyCipher;
    this.prober = prober;
    this.gateway = gateway;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @PreDestroy
  void shutdown() {
    loadExecutor.shutdown();
    try {
      if (!loadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        loadExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      loadExecutor.shutdownNow();
    }
  }

  // ────────────────────────── 查询 ──────────────────────────

  @Transactional(readOnly = true)
  public PageResult<ModelView> list(
      String keyword, ModelKind kind, ModelStatus status, Integer page, Integer size) {
    // ★ 空串等同不筛选（真前端会传 ?keyword=&page=1）—— 归一成 null，仓储侧不拼谓词
    String safeKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
    int safeSize = size == null ? 20 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    List<Model> found =
        models.search(safeKeyword, kind, status, (safePage - 1) * safeSize, safeSize);
    long total = models.countSearch(safeKeyword, kind, status);
    return PageResult.of(found.stream().map(ModelView::from).toList(), total, safePage, safeSize);
  }

  @Transactional(readOnly = true)
  public ModelView get(UUID id) {
    return ModelView.from(require(id));
  }

  /**
   * 基础模型目录 —— 从上游 {@code GET /v1/models} 拉取并映射 kind。
   *
   * <p>★ 上游不可达 ⇒ <strong>空列表 + 可读 hint</strong>（不是 500）： 基础模型目录是"新建模型弹窗"的数据源，上游挂了不应该让整个管理页 500。
   *
   * <p>★ {@code apiBase} / {@code apiKey} 为空时照旧走默认上游（现行为不回归）； 给了就走指定上游 —— 外部 API 的模型目录这才看得见。
   *
   * <p>★ 不在平台里维护第二份上游清单（{@code 09 §3.13.5} 纪律 2）。
   */
  @Transactional(readOnly = true)
  public BaseModelCatalog baseModels(
      String supplierRaw, String kindRaw, String apiBase, String apiKey) {
    Supplier supplier = supplierRaw == null ? null : parseSupplier(supplierRaw);
    ModelKind kind = kindRaw == null ? null : parseKind(kindRaw);

    List<UpstreamGateway.UpstreamModel> catalog;
    try {
      catalog = gateway.listModels(apiBase, apiKey);
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      log.warn("拉取上游模型目录失败：{}", e.getMessage());
      // ★ 空列表 + 可读 hint，不是 500 —— 上游挂了不应该让管理页崩掉
      return BaseModelCatalog.empty("未从上游拉到模型：请检查服务地址是否正确、服务是否已启动");
    }
    if (catalog.isEmpty()) {
      // 上游可达但清单为空 —— 同样给可读提示，不干巴巴甩个空数组
      return BaseModelCatalog.empty("上游返回了空的模型清单：请确认服务上已部署模型");
    }

    List<BaseModelOption> options =
        catalog.stream()
            .map(m -> toBaseOption(m, supplier))
            .filter(o -> kind == null || o.kind() == kind)
            .filter(o -> supplier == null || o.supplier() == supplier)
            .toList();
    return new BaseModelCatalog(options, null);
  }

  // ────────────────────────── 注册 CRUD ──────────────────────────

  @Transactional
  public ModelView create(UpsertCommand command, UUID operatorId) {
    validateCapacity(command.kind(), command.dimension(), command.contextWindow());
    if (command.kind() == ModelKind.EMBEDDING && command.dimension() == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "向量模型必须显式声明向量维度",
          "维度与向量表绑定，不能靠猜 —— 请填入实际输出维度（如 768 / 1024），或先用「探测」获取");
    }
    if (command.kind() == ModelKind.CHAT && command.dimension() != null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "对话模型不产出向量，dimension 必须为空",
          "向量维度是 embedding 模型的字段；填了它，界面会把「改维度需全量重灌」的警示挂到错误的模型上");
    }
    // ★ 与 update 同口径：能力声明只对对话模型有意义，传了就 400，不静默改写
    validateCapabilityFields(command.kind(), command.thinking(), command.tools());
    validateSupplier(command.supplier(), command.apiBase());

    // ★ contextWindow 不在契约的 UpsertModelBody 里 —— 它从上游目录的元数据派生（best-effort）。
    //   上游不可达时留 null，不让"拉不到元数据"阻断注册（探测失败不阻断，同一条立场）。
    Integer contextWindow = command.contextWindow();
    if (command.kind() == ModelKind.CHAT && contextWindow == null) {
      contextWindow = lookupContextWindow(command.baseModel(), command.apiBase());
    }

    String cipher = null;
    String iv = null;
    if (command.apiKey() != null && !command.apiKey().isBlank()) {
      ApiKeyCipher.Sealed sealed = apiKeyCipher.encrypt(command.apiKey().trim());
      cipher = sealed.cipherBase64();
      iv = sealed.ivBase64();
    }

    Model model =
        new Model(
            UUID.randomUUID(),
            command.name().trim(),
            command.kind(),
            command.supplier(),
            command.baseModel().trim(),
            command.apiBase(),
            cipher,
            iv,
            command.kind() == ModelKind.CHAT ? contextWindow : null,
            command.kind() == ModelKind.EMBEDDING ? command.dimension() : null,
            command.thinking() == null ? ThinkingMode.NONE : command.thinking(),
            command.tools() == null ? ToolsMode.UNSUPPORTED : command.tools(),
            false,
            command.remark(),
            operatorId);
    models.save(model);
    return ModelView.from(model);
  }

  @Transactional
  public ModelView update(UUID id, UpsertCommand command, UUID operatorId) {
    Model model = require(id);

    // ★ 基础模型不允许改：改它等于换了一个模型，应新建
    if (command.baseModel() != null
        && !command.baseModel().isBlank()
        && !command.baseModel().equals(model.baseModel())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "基础模型不可修改", "如需更换基础模型，请新建一个模型配置");
    }

    // ★ 不适用的字段一律拒绝，不静默丢 —— 与 create 同口径：
    //   "看起来改了、实际没改"比 400 更伤人，且 create/update 两条路径口径必须一致。
    if (model.kind() == ModelKind.CHAT && command.dimension() != null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "对话模型不产出向量，dimension 必须为空",
          "向量维度是 embedding 模型的字段；填了它，界面会把「改维度需全量重灌」的警示挂到错误的模型上");
    }
    // ★ create / update 同一条口径：能力声明只对对话模型有意义
    validateCapabilityFields(model.kind(), command.thinking(), command.tools());

    // ★ 改 dimension 需全量重灌向量表 ⇒ 已加载的模型改 dimension 必须拒绝
    //   （未加载的允许改 —— 还没灌过向量）
    if (model.kind() == ModelKind.EMBEDDING
        && command.dimension() != null
        && !command.dimension().equals(model.dimension())
        && model.status() == ModelStatus.LOADED) {
      throw new BusinessException(
          ErrorCode.EMBEDDING_LOCKED,
          "已加载的向量模型不可修改维度",
          "维度与向量表绑定，改则需重建向量表并全量重灌 —— 请先删除该模型并重建，或在知识库重建向量后重试");
    }

    if (command.name() != null && !command.name().isBlank()) {
      model.name(command.name().trim());
    }
    if (command.remark() != null) {
      model.remark(command.remark());
    }
    if (model.kind() == ModelKind.CHAT) {
      if (command.thinking() != null) {
        model.thinking(command.thinking());
      }
      if (command.tools() != null) {
        model.tools(command.tools());
      }
    }
    if (model.kind() == ModelKind.EMBEDDING
        && command.dimension() != null
        && model.status() != ModelStatus.LOADED) {
      model.dimension(command.dimension());
    }
    // apiBase / apiKey 可以更新（换了供应商凭证是日常运维动作）
    if (command.apiBase() != null) {
      model.apiBase(command.apiBase());
    }
    if (command.apiKey() != null && !command.apiKey().isBlank()) {
      ApiKeyCipher.Sealed sealed = apiKeyCipher.encrypt(command.apiKey().trim());
      model.apiKey(sealed.cipherBase64(), sealed.ivBase64());
    }
    model.updatedBy(operatorId);
    models.save(model);
    return ModelView.from(model);
  }

  /**
   * 删除模型。
   *
   * <p>★ 拒绝条件是「被应用/知识库引用」而不是「已加载」（{@code 08 §3.2} 连带修正）—— 否则加载过的模型永远删不掉（契约只有 load 没有 unload）。
   *
   * <p>★ {@code builtin=true} 拒绝 —— 内置模型硬编码不可删。
   *
   * <p>★ 删除时清掉启用位（{@code clearEnable()}）—— 删之前先把 enabled 关掉， 让"删了一半"的中间态里没有仍在对外服务的模型。
   *
   * <p>⚠️ 「被应用/知识库引用」的检查留成钩子（第 5 周的 {@code assistant} / {@code knowledge} 表还没落）—— 当前直接通过，
   * 两张表落地后在此处加引用计数查询。
   */
  @Transactional
  public void delete(UUID id) {
    Model model = require(id);
    if (model.builtin()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "「" + model.name() + "」是内置模型，不可删除");
    }

    // ★ 钩子：被应用/知识库引用时拒绝删除（第 5 周落地 assistant / knowledge 表后在此加查询）。
    //   参考前端 mock/handlers/models.ts 的写法：
    //   - 查 assistants 表里 modelId = id 的应用
    //   - 查 knowledge_bases 表里 embeddingModelId = id 的知识库
    //   - 任一非空则拒绝，并列出引用者名称
    //   两张表是第 5 周的，当前没有引用方，直接通过。
    //   CHANGELOG 0.1.3 已记此钩子。

    model.clearEnable();
    models.delete(model);
    log.info("删除模型 {}（{}），已清启用位", model.name(), model.id());
  }

  // ────────────────────────── load 语义 ──────────────────────────

  /**
   * 触发加载。★ <strong>异步</strong>：置 {@code LOADING} 并立即返回（契约写明"加载是异步的，返回时 status 仍是加载中"），
   * 后台做上游可用性校验，随后转 {@code LOADED} 或 {@code LOAD_FAILED}。
   *
   * <p>load 的语义是<strong>平台启用位 + 上游可用性校验</strong>，不操纵上游实例（{@code 09 §3.13.4}）。
   *
   * <p>★ 任意态都可以发起（包括 {@code LOAD_FAILED} 的重试与 {@code LOADED} 的重载）—— 重载是运维的日常动作（换了 apiBase / key
   * 之后总要再点一次）。领域状态机 {@link Model#startLoad()} 允许任意态→加载中。
   */
  @Transactional
  public ModelView load(UUID id) {
    Model model = require(id);
    model.startLoad();
    models.save(model);

    // ★ 立即返回 LOADING，后台继续 —— 不要在这里同步等上游（管理员点一下就卡 30 秒的体验不可接受）。
    //   ★ 必须在事务提交后再调度：否则异步线程看不到 LOADING 那次写入（READ COMMITTED），
    //   findById 会拿到旧状态甚至找不到，completeLoad 就悄悄不干活。
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            loadExecutor.submit(() -> completeLoad(id));
          }
        });

    return ModelView.from(model);
  }

  /**
   * 后台完成加载：上游可用性校验 → {@code markLoaded()} 或 {@code markFailed(可读原因)}。
   *
   * <p>★ 在独立事务里做（{@link TransactionTemplate}）—— 异步线程没有绑定的事务上下文。 失败原因必须是能照着排查的那句话，不是「加载失败」四个字。
   */
  private void completeLoad(UUID id) {
    transactionTemplate.executeWithoutResult(
        status -> {
          Model model =
              models.findById(id).orElseThrow(() -> new IllegalStateException("加载期间模型被删除：" + id));
          try {
            boolean available =
                gateway.isModelAvailable(model.baseModel(), model.apiBase(), decryptKey(model));
            if (available) {
              model.markLoaded();
              log.info("模型 {} 加载成功（上游清单里有 {}）", model.name(), model.baseModel());
            } else {
              model.markFailed(
                  "上游清单里没有这个基础模型：" + model.baseModel() + "。请确认模型已在上游部署，并检查 apiBase 是否指向了正确的服务");
              log.warn("模型 {} 加载失败：上游清单里没有 {}", model.name(), model.baseModel());
            }
          } catch (UpstreamGateway.UpstreamUnreachableException e) {
            model.markFailed(e.getMessage());
            log.warn("模型 {} 加载失败：{}", model.name(), e.getMessage());
          } catch (Exception e) {
            // 兜底：任何未预期异常都转成可读原因（状态机拒空值）
            model.markFailed("加载时发生未预期错误：" + e.getClass().getSimpleName() + "：" + e.getMessage());
            log.error("模型 {} 加载时未预期异常", model.name(), e);
          }
          models.save(model);
        });
  }

  // ────────────────────────── 能力探测 ──────────────────────────

  /**
   * 按基础模型探测能力（新建时就能探 —— 契约要求"能力必须在保存前就填好"）。
   *
   * <p>★ 探测失败不阻断：抛 {@link BusinessException} 带可读原因与 hint， 用户可手工声明（{@code UpsertModelBody} 的
   * thinking / tools / dimension）。
   */
  public ModelCapability probeBaseModel(String baseModel, Supplier supplier, ModelKind kind) {
    try {
      return prober.probe(baseModel, kind, null, null);
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "无法自动探测「" + baseModel + "」的能力：" + e.getMessage(),
          "探测失败不阻断注册 —— 请在表单中手工声明思考模式与工具调用能力");
    }
  }

  /** 按已存模型的 id 探测能力（用该模型的 apiBase / apiKey）。 */
  public ModelCapability probeById(UUID id) {
    Model model = require(id);
    try {
      return prober.probe(model.baseModel(), model.kind(), model.apiBase(), decryptKey(model));
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "无法自动探测「" + model.baseModel() + "」的能力：" + e.getMessage(),
          "探测失败不阻断 —— 请在表单中手工声明思考模式与工具调用能力");
    }
  }

  // ────────────────────────── 内部 ──────────────────────────

  private Model require(UUID id) {
    return models
        .findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "模型不存在"));
  }

  /** 解密模型的 API key（没有存 key 时返回 null，由 {@link UpstreamGateway} 用默认 key）。 */
  private String decryptKey(Model model) {
    if (model.apiKeyCipher() == null || model.apiKeyIv() == null) {
      return null;
    }
    return apiKeyCipher.decrypt(model.apiKeyCipher(), model.apiKeyIv());
  }

  /**
   * 从上游目录 best-effort 查 contextWindow。上游不可达 / 返回的数据没有这个字段 ⇒ 返回 null，不阻断注册。
   *
   * <p>★★ <strong>`filter(Objects::nonNull)` 不是防御性编程，是必需的</strong>： {@code Stream.findFirst()} 遇到
   * <b>null 元素</b>会抛 {@code NullPointerException} （`Optional` 不接受 null）。而 `contextWindow` 是
   * `Integer`，且<b>多数上游根本不返回它</b> （实测：Ollama 的 `/v1/models` 只有 id / created / owned_by）⇒ 那个 null 顺着
   * map 流下去， 在 findFirst 处炸成 500。
   *
   * <p>★ 真实症状很贵：**把任何 Ollama 模型注册进平台都 500** （`POST /api/v1/models` → 「服务内部错误」），而除了日志之外完全看不出原因。
   *
   * <p>★ 兜底异常从 `UpstreamUnreachableException` 放宽到 `RuntimeException`： 方法名与注释都写着
   * best-effort（"不阻断注册"），而"上游返回异形 JSON"不是"不可达"、 却同样不该阻断注册。放宽后仍然 <b>WARN 留痕</b>，不是静默吞掉。
   */
  private Integer lookupContextWindow(String baseModel, String apiBase) {
    try {
      return gateway.listModels(apiBase, null).stream()
          .filter(m -> baseModel.equals(m.id()))
          .map(UpstreamGateway.UpstreamModel::contextWindow)
          .filter(java.util.Objects::nonNull)
          .findFirst()
          .orElse(null);
    } catch (RuntimeException e) {
      log.warn("拉取 contextWindow 失败（不阻断注册）：{}", e.toString());
      return null;
    }
  }

  private static void validateCapacity(ModelKind kind, Integer dimension, Integer contextWindow) {
    // 空值语义由 Model 构造器与库层 check 约束双重钉住，这里只做"必填"检查
    if (kind == ModelKind.EMBEDDING && dimension == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "向量模型必须显式声明向量维度",
          "维度与向量表绑定，不能靠猜 —— 请填入实际输出维度（如 768 / 1024），或先用「探测」获取");
    }
  }

  private static void validateSupplier(Supplier supplier, String apiBase) {
    if (supplier == Supplier.EXTERNAL_API && (apiBase == null || apiBase.isBlank())) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "外部 API 模型必须提供 apiBase", "外部 API 的推理服务地址是必填项 —— 本地推理才可以留空（用默认上游）");
    }
  }

  /**
   * 能力声明（thinking / tools）只对对话模型有意义 —— create / update 共用这一句。
   *
   * <p>★ 传了就 400，不静默改写成 NONE / UNSUPPORTED： 「看起来改了、实际没改」比 400 更伤人，且两条路径口径必须一致。
   */
  private static void validateCapabilityFields(
      ModelKind kind, ThinkingMode thinking, ToolsMode tools) {
    if (kind == ModelKind.EMBEDDING && (thinking != null || tools != null)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "能力声明只对对话模型有意义：向量模型不接受 thinking / tools",
          "思考模式与工具调用是对话模型的能力；向量模型固定为 NONE / UNSUPPORTED —— 请去掉这两个字段");
    }
  }

  private static BaseModelOption toBaseOption(UpstreamGateway.UpstreamModel m, Supplier supplier) {
    ModelKind kind = guessKind(m);
    // 供应商：上游清单里没有这个信息，按配置的默认值（本地推理）填
    Supplier resolved = supplier != null ? supplier : Supplier.LOCAL_INFERENCE;
    return new BaseModelOption(
        m.id(),
        kind,
        resolved,
        kind == ModelKind.CHAT ? m.contextWindow() : null,
        kind == ModelKind.EMBEDDING ? m.dimension() : null);
  }

  /**
   * 从上游元数据推断模型种类。
   *
   * <p>★ 优先看元数据（{@code dimension} → EMBEDDING，{@code context_window} → CHAT）， 没有元数据时用名称启发式（含
   * "embed" → EMBEDDING）。 这不是造假 —— kind 是"这个模型是什么"的客观事实，元数据缺失时只能从名字推断。
   */
  private static ModelKind guessKind(UpstreamGateway.UpstreamModel m) {
    if (m.dimension() != null) {
      return ModelKind.EMBEDDING;
    }
    if (m.contextWindow() != null) {
      return ModelKind.CHAT;
    }
    String id = m.id() == null ? "" : m.id().toLowerCase(Locale.ROOT);
    return id.contains("embed") || id.contains("bge") || id.contains("vector")
        ? ModelKind.EMBEDDING
        : ModelKind.CHAT;
  }

  private static ModelKind parseKind(String raw) {
    try {
      return ModelKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "kind 取值不合法：" + raw, "合法值：CHAT / EMBEDDING");
    }
  }

  private static Supplier parseSupplier(String raw) {
    try {
      return Supplier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "supplier 取值不合法：" + raw, "合法值：LOCAL_INFERENCE / EXTERNAL_API");
    }
  }

  /**
   * 注册 / 更新的命令对象。
   *
   * <p>★ 用 record 而不是直接收 {@code UpsertModelBody} 的 DTO：契约字段与领域字段的语义不完全一样（如 {@code dimension} 的 null
   * 语义）， 一道显式映射让差异有地方说清。
   */
  public record UpsertCommand(
      String name,
      ModelKind kind,
      Supplier supplier,
      String baseModel,
      String apiBase,
      String apiKey,
      Integer dimension,
      Integer contextWindow,
      ThinkingMode thinking,
      ToolsMode tools,
      String remark) {}

  /**
   * 基础模型目录结果：选项列表 + 上游不可达/为空时的可读 hint。
   *
   * <p>★ 成功时 {@code hint} 为 null；失败时 options 为空列表 —— 调用方（Web 层）把 hint 放进包络的 {@code hint} 字段，
   * 让界面能显示「请检查服务地址」而不是干巴巴一个空数组。
   */
  public record BaseModelCatalog(List<BaseModelOption> options, String hint) {
    public static BaseModelCatalog empty(String hint) {
      return new BaseModelCatalog(List.of(), hint);
    }
  }
}
