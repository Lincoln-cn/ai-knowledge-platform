package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersionRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 已发布版本的<strong>只读</strong>查询（{@code #198} 第五块；契约 {@code GET /assistants/{appId}/workflow/versions}
 * 与 {@code GET /assistants/{appId}/workflow/versions/:version}）。
 *
 * <p>★ 本类只读、不写：版本是<strong>只能追加</strong>的账（{@link WorkflowVersionRepository} 的类注释）， 读它不产生审计、不改任何行 ——
 * 所以没有 {@code @Transactional} 写事务，取了 {@code readOnly = true}。
 *
 * <p>★ 三条判定都落在<strong>这里</strong>而不是控制器：应用存不存在、版本存不存在、版本是不是这个应用的。 控制器只负责"HTTP 形状"（路径段 → 方法、返回值 →
 * 包络），把越权判定放在那里，换一个入口就会漏一遍。
 */
@Service
public class WorkflowVersionQueryService {

  private final WorkflowDraftService drafts;
  private final WorkflowVersionRepository versions;
  private final ObjectMapper json;

  public WorkflowVersionQueryService(
      WorkflowDraftService drafts, WorkflowVersionRepository versions, ObjectMapper json) {
    this.drafts = drafts;
    this.versions = versions;
    this.json = json;
  }

  /**
   * 版本列表，按版本号<strong>降序</strong>。
   *
   * <p>★ 从没发布过 ⇒ <strong>空列表</strong>，不是 404：一个还没发布的编排是正常状态。 404 的语义是"这个应用不存在" —— 把空历史说成
   * 404，等于让人去查"应用是不是被删了"。
   *
   * @throws BusinessException 404 应用不存在（文案与应用草稿端点同一句，复用了 {@code requireAssistant}）
   */
  @Transactional(readOnly = true)
  public List<Version> list(UUID assistantId) {
    drafts.requireAssistant(assistantId);
    return versions.history(assistantId).stream().map(this::version).toList();
  }

  /**
   * 某一版的<strong>图快照</strong>（原样解析成 JSON 对象，不二次转义）。
   *
   * <p>★ 响应体是裸图：前端 {@code previewVersion} 拿到它就交给 {@code renderGraphData}， 包一层 {@code {graph: …}}
   * 会让页面读到 {@code undefined.nodes}。
   *
   * @param versionKey 版本行主键（UUID）或该应用内的版本号（整数），见 {@link #require(UUID, String)}
   * @throws BusinessException 404 应用不存在 / 404 版本不存在或不属于这个应用
   */
  @Transactional(readOnly = true)
  public JsonNode graph(UUID assistantId, String versionKey) {
    drafts.requireAssistant(assistantId);
    return json.readTree(require(assistantId, versionKey).graph());
  }

  /**
   * 解析路径段并取出<strong>属于这个应用</strong>的那一行。
   *
   * <p>★ 两种写法都认：主键（UUID，写操作台账里的命名）与该应用内的版本号（整数，冻结契约 {@code getWorkflowVersionGraph(appId, version)}
   * 与页面 {@code previewVersion(item.version)} 真正传的东西）。 只认一种，另一种就会静默 404 —— 页面那条 catch
   * 会把它显示成"还没接后端"，看起来像部署没生效。
   *
   * <p>★ 两条路都<strong>必须</strong>按应用收窄：主键那条读的是全局行，靠 {@code assistantId} 比一次； 版本号那条在查询里就带上了 {@code
   * assistant_id}。少了任何一边，"别的应用的版本"就能被读到 —— 那是跨应用读取，不是"多返一个字段"。
   */
  private WorkflowVersion require(UUID assistantId, String versionKey) {
    if (versionKey != null && !versionKey.isBlank()) {
      Optional<UUID> asId = asUuid(versionKey);
      if (asId.isPresent()) {
        return versions
            .findById(asId.get())
            .filter(row -> assistantId.equals(row.assistantId()))
            .orElseThrow(() -> notFound(versionKey));
      }
      Optional<Integer> asNumber = asVersionNumber(versionKey);
      if (asNumber.isPresent()) {
        return versions
            .findVersion(assistantId, asNumber.get())
            .orElseThrow(() -> notFound(versionKey));
      }
    }
    throw notFound(versionKey);
  }

  /**
   * 版本列表项（契约 {@code WorkflowVersion}）。
   *
   * <p>★ 刻意<strong>不带</strong> {@code graph}：列表要的是"有哪几版、谁发的、几个节点"， 每次发布都抄一份的图放进列表，会让这个响应随历史线性膨胀。
   */
  public record Version(
      UUID id, int version, Instant publishedAt, UUID publishedBy, String note, int nodeCount) {}

  /** 列表项 = 行 + 从<strong>快照</strong>里数出来的节点数。 */
  private Version version(WorkflowVersion row) {
    return new Version(
        row.id(),
        row.version(),
        row.publishedAt(),
        row.publishedBy(),
        row.note(),
        nodeCount(row.graph()));
  }

  /**
   * 数节点：{@code nodes} 数组的长度，从<strong>图快照</strong>里数。
   *
   * <p>★ 为什么不另存一列：多一列就多一处能对不上（发布时数的那个数与后来读到的图）， 而快照本身已经是真源。 ★ 为什么不数草稿：草稿是"正在画的那张"，不是"线上正在跑的那张" ——
   * 数错的那一次， 会给出一个用户会照着做决定的数量。
   *
   * <p>★ 形状不对（没有 {@code nodes}、或它不是数组）时返回 0 而不是炸：发布时已校验过， 这里读到异常形状说明库里的行被人改过 —— 此时"列表整页 500"比"某一版显示
   * 0 个节点"更难排查。
   */
  private int nodeCount(String graph) {
    JsonNode nodes = json.readTree(graph).path("nodes");
    return nodes.isArray() ? nodes.size() : 0;
  }

  /**
   * 「这一版拿不到」—— 不存在与<strong>不属于这个应用</strong>共用同一句文案与同一个码。
   *
   * <p>★ 区分它们等于告诉越权者"这个 id 真实存在"（{@code 08 §4.0} 第 4 行，也是 {@link ErrorCode#NOT_FOUND} 的注释）。
   */
  private static BusinessException notFound(String versionKey) {
    return new BusinessException(
        ErrorCode.NOT_FOUND, "版本不存在：" + versionKey, "先看发布历史里有没有这一版 —— 版本号从 1 开始，按应用各自递增");
  }

  private static Optional<UUID> asUuid(String key) {
    try {
      return Optional.of(UUID.fromString(key));
    } catch (IllegalArgumentException notAUuid) {
      return Optional.empty();
    }
  }

  private static Optional<Integer> asVersionNumber(String key) {
    try {
      return Optional.of(Integer.valueOf(key));
    } catch (NumberFormatException notANumber) {
      return Optional.empty();
    }
  }
}
