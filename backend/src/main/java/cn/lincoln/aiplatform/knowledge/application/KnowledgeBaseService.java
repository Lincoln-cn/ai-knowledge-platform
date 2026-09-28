package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.ImportType;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库建档（第 5 周 · 0.2.0 批 1）—— 七个端点的用例层。
 *
 * <h3>★ 三条纪律（{@code 08 §4.4}</h3>
 *
 * <ol>
 *   <li><strong>列表按 {@code dataScope} 拼进查询</strong>（仓储的 {@code Specification}），不是查完再筛
 *   <li><strong>看不见的库一律 404 而不是 403</strong> —— 403 等于确认"这个 id 存在"，是可被枚举的信息泄漏
 *   <li><strong>ownerId / visibility 不接受前端传</strong>：创建时 ownerId = JWT 的 {@code sub}，visibility =
 *       {@code PRIVATE}（一律收窄）
 * </ol>
 *
 * <h3>★ 幂等（{@code clientRequestId}）</h3>
 *
 * <p>同一 {@code clientRequestId} 重复创建返回<strong>既有库</strong>，而不是「名称已存在」。 原因：建库成功但后续上传失败后，用户会点「重试」——
 * 若第二次创建被"名称已存在"卡住， 用户既不能继续导入、也找不到刚才那个库；换个名字再建则留下孤儿库。 幂等键的唯一性是 (clientRequestId, ownerId)：
 * 幂等是「同一个调用方的重试」，不是「全平台一个命名空间」。
 */
@Service
public class KnowledgeBaseService {

  private static final int MAX_PAGE_SIZE = 100;

  /** 分类清单的兜底字典（与前端 mock 的四类对齐）。库里的其它分类会追加在后面。 */
  private static final List<String> DEFAULT_CATEGORIES = List.of("制度规范", "产品资料", "行业标准", "其他");

  private final KnowledgeBaseRepository bases;
  private final AccessScopePort accessScope;

  public KnowledgeBaseService(KnowledgeBaseRepository bases, AccessScopePort accessScope) {
    this.bases = bases;
    this.accessScope = accessScope;
  }

  // ────────────────────────── 查询 ──────────────────────────

  @Transactional(readOnly = true)
  public PageResult<KnowledgeBaseView> list(
      String category, Boolean enabled, Boolean mine, String keyword, Integer page, Integer size) {
    // ★ 空串等同不筛选（真前端会传 ?keyword=&page=1）—— 归一成 null，仓储侧不拼谓词
    String safeKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
    String safeCategory = (category == null || category.isBlank()) ? null : category.trim();
    int safeSize = size == null ? 20 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    boolean safeMine = mine != null && mine;

    ScopeFilter scope = accessScope.currentScope();
    List<KnowledgeBase> found =
        bases.search(
            scope,
            safeCategory,
            enabled,
            safeMine,
            safeKeyword,
            (safePage - 1) * safeSize,
            safeSize);
    // ★ countSearch 与 search 共用同一份谓词 —— total 必须是过滤之后的数
    long total = bases.countSearch(scope, safeCategory, enabled, safeMine, safeKeyword);
    return PageResult.of(found.stream().map(this::toView).toList(), total, safePage, safeSize);
  }

  /**
   * 单条详情。★ <strong>看不见 ⇒ 404</strong>（而不是 403）： 返 403 等于确认"这个 id 存在但你没权限"，那是可被枚举的信息泄漏（{@code 08
   * §4.4.3} 纪律 2）。
   */
  @Transactional(readOnly = true)
  public KnowledgeBaseView get(UUID id) {
    return toView(requireVisible(id));
  }

  /** 分类清单（含每个分类下当前用户可见的库数）。 */
  @Transactional(readOnly = true)
  public List<KnowledgeCategoryView> categories() {
    ScopeFilter scope = accessScope.currentScope();
    var counts = bases.countByCategory(scope);
    java.util.Map<String, Long> byName = new java.util.LinkedHashMap<>();
    for (Object[] row : counts) {
      byName.put((String) row[0], (Long) row[1]);
    }
    List<KnowledgeCategoryView> result = new java.util.ArrayList<>();
    // 兜底字典在前（界面需要稳定的筛选项，哪怕某类下暂时 0 个库）
    for (String name : DEFAULT_CATEGORIES) {
      result.add(new KnowledgeCategoryView(name, name, byName.getOrDefault(name, 0L)));
    }
    // 库里出现的其它分类追加在后，避免"建了新分类却在筛选器里找不到"
    byName.forEach(
        (name, count) -> {
          if (!DEFAULT_CATEGORIES.contains(name)) {
            result.add(new KnowledgeCategoryView(name, name, count));
          }
        });
    return result;
  }

  // ────────────────────────── 创建 ──────────────────────────

  /**
   * 建库。
   *
   * <p>★ {@code ownerId} 只能是当前用户（从 JWT 的 {@code sub} 取），<em>不接受前端传</em>； {@code visibility} 一律
   * {@code PRIVATE}。
   *
   * <p>★ {@code maxClassification}（ceiling）<strong>必填</strong>：缺了它「文档密级 ≤ ceiling」的入库校验就没有依据（{@code
   * 13 §6}）。
   *
   * <p>★ 幂等：同一 {@code clientRequestId} 重复创建返回既有库。为什么见类注释。
   */
  @Transactional
  public KnowledgeBaseView create(
      String name,
      String category,
      String description,
      String icon,
      ImportType importType,
      UUID embeddingModelId,
      ClassificationLevel maxClassification,
      String clientRequestId,
      UUID currentUserId) {
    if (maxClassification == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "库的密级上限必填", "为这个知识库选择允许装入的最高密级（公开/内部/秘密/机密）—— 它是入库校验的依据（13 §6）");
    }
    if (name == null || name.isBlank() || name.length() > 30) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库名称必须是 1–30 字");
    }
    if (description != null && description.length() > 200) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库描述不能超过 200 字");
    }

    // ★ 幂等重放：先查既有库，命中就直接返回 —— 不是「名称已存在」
    if (clientRequestId != null && !clientRequestId.isBlank()) {
      var existing = bases.findByClientRequestIdAndOwnerId(clientRequestId.trim(), currentUserId);
      if (existing.isPresent()) {
        return toView(existing.get());
      }
    }

    KnowledgeBase base;
    try {
      base =
          new KnowledgeBase(
              UUID.randomUUID(),
              name,
              category,
              description,
              icon,
              importType,
              embeddingModelId,
              maxClassification,
              currentUserId,
              clientRequestId == null || clientRequestId.isBlank() ? null : clientRequestId.trim(),
              currentUserId);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
    }
    return toView(bases.save(base));
  }

  // ────────────────────────── 更新 ──────────────────────────

  /** 改库。★ 看不见的库返 404（同 {@link #get}）。 ownerId 不可改 —— 转让是显式动作，不是改个字段。 */
  @Transactional
  public KnowledgeBaseView update(
      UUID id,
      String name,
      String category,
      String description,
      String icon,
      ClassificationLevel maxClassification,
      UUID currentUserId) {
    KnowledgeBase base = requireVisible(id);
    try {
      if (name != null) {
        base.rename(name);
      }
      if (category != null) {
        base.changeCategory(category);
      }
      if (description != null) {
        base.changeDescription(description);
      }
      if (icon != null) {
        base.changeIcon(icon);
      }
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
    }
    // ceiling 可改（尚无文档时改是安全的）；有文档后的收紧/放宽校验随文档段补
    if (maxClassification != null) {
      base.changeMaxClassification(maxClassification);
    }
    base.setUpdatedBy(currentUserId);
    return toView(bases.save(base));
  }

  /** 启用/停用。★ 看不见的库返 404。 */
  @Transactional
  public KnowledgeBaseView setEnabled(UUID id, boolean enabled, UUID currentUserId) {
    KnowledgeBase base = requireVisible(id);
    if (enabled) {
      base.enable();
    } else {
      base.disable();
    }
    base.setUpdatedBy(currentUserId);
    return toView(bases.save(base));
  }

  /** 删除（软删）。★ 看不见的库返 404。 文档与分段的连带删除随文档段落地；本段只落聚合根的软删（{@code 02 §5.1}：软删仅聚合根）。 */
  @Transactional
  public void delete(UUID id, UUID currentUserId) {
    KnowledgeBase base = requireVisible(id);
    base.markDeleted(Instant.now(), currentUserId);
    bases.save(base);
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 按 id 取库，并做可见性判定。★ 不可见与不存在<strong>同一个</strong> {@link ErrorCode#NOT_FOUND}。
   *
   * <p>★ 用 {@link AccessScopePort#canSee} 而不是自己再写一遍"看 ownerId、再看 visibility、再看 grants" —— 那正是
   * {@code OwnedResource} 要消灭的复制（{@code 07 §3.3}）。
   */
  private KnowledgeBase requireVisible(UUID id) {
    KnowledgeBase base =
        bases
            .findById(id)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在", "它可能已被删除，或你没有访问权限"));
    if (!accessScope.canSee(base)) {
      // ★ 404 而不是 403：区分"看不见"与"不存在"等于告诉越权者这个 ID 真实存在
      throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在", "它可能已被删除，或你没有访问权限");
    }
    return base;
  }

  private KnowledgeBaseView toView(KnowledgeBase base) {
    // ★ embeddingModelName 暂回空串：向量模型的显示名属于 modelcatalog，
    //   而它的 domain 类型是模块内部 API（Modulith 会拦）。等模型引用/展示
    //   随文档段一起落地时，走 application 层的公开 API 填这个字段。
    return KnowledgeBaseView.from(base, "");
  }
}
