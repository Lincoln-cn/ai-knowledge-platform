package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.AccessCredential;
import cn.lincoln.aiplatform.assistant.domain.AccessCredentialRepository;
import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 免登录访问凭证的服务层（第 16 周 · 0.4.4）。
 *
 * <h3>★ 判据 ①：撤销后<strong>立即</strong>失效</h3>
 *
 * <p>撤销是终态（契约 {@code revokeCredential} 的注释），而"终态"对使用者的含义是 <strong>现在就不能用了</strong>。本实现按 token
 * 缓存解析结果（省掉每次访问两次查库），于是 {@link #revoke} <strong>必须</strong>把那一项清掉（见 {@link
 * PublicCredentialResolver#forget}） —— 做不到就不该引这个缓存。判据 ① 的原始措辞是"在<strong>同一个请求周期内</strong>再访问即被拒"，
 * 所以这条链上不允许出现任何"等它失效"的环节。
 *
 * <h3>★ 判据 ②：创建时冻结（{@code 13 §5.2} 第 ① 级）</h3>
 *
 * <p>{@link #create} 的入参里<strong>根本没有</strong> {@code maxClassification}：冻结值由服务层从 {@code
 * accessScope.currentScope().maxClassification()} 取出（= 创建者此刻的 clearance）。 这比"读了请求体再丢掉"强 ——
 * 调用方<strong>无法表达</strong>一个更高的密级，而不是"表达了不生效"。
 *
 * <h3>★ 判据 ③：每次访问实时再滤（{@code 13 §5.2} 第 ② 级）</h3>
 *
 * <p>{@link #preview} 每次都用<strong>冻结值</strong>重新跑一遍过滤（走 {@link
 * AccessScopePort#asScope}），<strong>不是</strong>复用创建时算出来的结果。所以 "内容升密 ⇒ 同一条 token 的可见集合收窄"成立。
 *
 * <h3>★ 五种失败原因分得开</h3>
 *
 * <table>
 *   <tr><th>原因</th><th>码</th><th>文案关键字</th><th>为什么这样分</th></tr>
 *   <tr><td>不存在</td><td>{@code 404}</td><td>不存在</td>
 *       <td>与"看不见的资源返 404"同一条纪律：不确认这个 token 存在过</td></tr>
 *   <tr><td>已撤销</td><td>{@code 403}</td><td>已撤销</td>
 *       <td>终态，只能重新生成</td></tr>
 *   <tr><td>已过期</td><td>{@code 403}</td><td>已过期</td>
 *       <td>时间到了，处置与"撤销"不同（可以重新生成一条同样用途的）</td></tr>
 *   <tr><td>已停用</td><td>{@code 403}</td><td>已停用</td>
 *       <td>可恢复：管理员再打开即可 —— 与撤销混淆会让人白白重建一条链接</td></tr>
 *   <tr><td>越权</td><td>{@code 403}</td><td>无权限 / 无法以别人的身份</td>
 *       <td>唯一一种"对方是合法登录用户、却不该碰这条凭证"的情形</td></tr>
 * </table>
 *
 * <p>★ 全返同一个码会让排障只能猜。而码只能取契约里已有的那几个（本轮不许新增错误码）， 于是"哪一种状态"由<strong>文案</strong>承载（{@code 08
 * §4.1}：码管分支、文案管解释）。
 *
 * <h3>★ 权限收窄只发生在一处</h3>
 *
 * <p>"一条链接的生效范围不许超过解析它的人"这件事<strong>不在这里</strong>判 —— 它在 {@link
 * AccessScopePort#asScope}（密级取更低、其余分量照抄请求者）。 也就是说：权限收窄与数据范围判定
 * <strong>同一个实现</strong>，本类只负责"状态"与"用冻结值重跑一次过滤"。
 */
@Service
public class AccessCredentialService {

  private static final Logger log = LoggerFactory.getLogger(AccessCredentialService.class);

  /** 预览时最多返回多少条命中。★ 它只是"别把整库拉进内存"的闸，不是分页。 */
  private static final int PREVIEW_LIMIT = 50;

  private final AssistantRepository assistants;
  private final AccessCredentialRepository credentials;
  private final SegmentSearchPort segmentSearch;
  private final AccessScopePort accessScope;
  private final PublicCredentialResolver resolver;

  public AccessCredentialService(
      AssistantRepository assistants,
      AccessCredentialRepository credentials,
      SegmentSearchPort segmentSearch,
      AccessScopePort accessScope,
      PublicCredentialResolver resolver) {
    this.assistants = assistants;
    this.credentials = credentials;
    this.segmentSearch = segmentSearch;
    this.accessScope = accessScope;
    this.resolver = resolver;
  }

  // ────────────────────────── 凭证管理（管理员面）──────────────────────────

  /** 列出某个应用的凭证（契约 {@code listCredentials}）。★ 含已撤销的 —— 契约要下发 {@code revokedAt}。 */
  @Transactional(readOnly = true)
  public List<AccessCredentialView> list(UUID appId, UUID requesterId) {
    Assistant app = requireVisibleApp(appId);
    requireOwner(app, requesterId);
    return credentials.findByAssistantId(app.id()).stream()
        .map(AccessCredentialView::from)
        .toList();
  }

  /**
   * 新建凭证（契约 {@code createCredential}，入参只有 {@code expiresAt}）。
   *
   * <p>★★ 冻结值取<strong>创建者此刻的 clearance</strong>：{@code currentScope().maxClassification()}。
   * 取不到（未认证）⇒ 领域构造器会拦下并说清原因 —— 方向是 fail-closed（不是"默认给最高"也不是 "默认给公开"：后者会造出一条谁都不知道密级不对的链接）。
   *
   * @param expiresAt null = 长期有效
   */
  @Transactional
  public AccessCredentialView create(UUID appId, Instant expiresAt, UUID requesterId) {
    Assistant app = requireVisibleApp(appId);
    requireOwner(app, requesterId);
    ClassificationLevel frozen = accessScope.currentScope().maxClassification();
    AccessCredential credential =
        new AccessCredential(
            UUID.randomUUID(),
            app.id(),
            AccessCredential.newToken(),
            frozen,
            expiresAt,
            requesterId);
    AccessCredential saved = credentials.save(credential);
    log.info(
        "应用 {} 新建访问凭证 {}，冻结密级 {}（= 创建者当时的 clearance）",
        app.id(),
        saved.id(),
        saved.maxClassification());
    return AccessCredentialView.from(saved);
  }

  /**
   * 启停一条凭证（契约 {@code setCredentialEnabled}）。
   *
   * <p>★ 撤销过的<strong>不许</strong>再打开（{@link AccessCredential#setEnabled}）：撤销是终态， 只能重新生成。库里另有 {@code
   * chk_as_credential_revoked_is_disabled} 兜底。
   */
  @Transactional
  public AccessCredentialView setEnabled(UUID appId, UUID id, boolean enabled, UUID requesterId) {
    Assistant app = requireVisibleApp(appId);
    requireOwner(app, requesterId);
    AccessCredential credential = requireCredentialOf(app, id);
    credential.setEnabled(enabled, requesterId);
    AccessCredential saved = credentials.save(credential);
    // ★ 停用必须立刻丢掉缓存：留着它，被停用的链接还能用一阵子 —— 而"停用"的含义就是"现在停"
    //   （与撤销同理）。启用方向不必清：缓存命中后仍会按"凭证还可用吗"复判。
    if (!enabled) {
      resolver.forget(saved.token());
    }
    return AccessCredentialView.from(saved);
  }

  /**
   * 撤销一条凭证（契约 {@code revokeCredential}）—— <strong>终态</strong>。
   *
   * <p>★★ 判据 ① 就落在这里：撤销之后<strong>必须在同一个请求周期内</strong>失效， 所以这里显式调用 {@link
   * PublicCredentialResolver#forget}。撤销与解析缓存<strong>成对</strong>存在 是本设计的硬要求 ——
   * 少了这一句，"撤销了还能用"会一直持续到缓存项因别的原因消失， 而<strong>没有任何报错</strong>。
   */
  @Transactional
  public AccessCredentialView revoke(UUID appId, UUID id, UUID requesterId) {
    Assistant app = requireVisibleApp(appId);
    requireOwner(app, requesterId);
    AccessCredential credential = requireCredentialOf(app, id);
    credential.revoke(requesterId);
    AccessCredential saved = credentials.save(credential);
    // ★★ 判据①：撤销即失效
    resolver.forget(saved.token());
    log.info("访问凭证 {} 已撤销（终态，不可重新启用）", saved.id());
    return AccessCredentialView.from(saved);
  }

  // ────────────────────────── 公开面（访客面）──────────────────────────

  /**
   * 用<strong>冻结密级</strong>重新执行一次过滤，返回这次访问能看得见的分段 （{@code 13 §5.2} 第 ② 级"每次访问实时再滤"）。
   *
   * <p>★ 它是判据 ③ 的载体：每调一次就跑一次过滤，所以两次访问之间内容升密 ⇒ 第二次的集合收窄。 复用"创建时算出来的结果"会让那条断言变红（金丝雀，见交付报告）。
   *
   * <p>★ 过滤本身<strong>只有一处实现</strong>：密级谓词在 SQL 里（{@code s.classification_rank <= ?}）， 而它的取值来自
   * {@link ScopeFilter} —— 这里通过 {@link AccessScopePort#asScope} 把冻结范围装上， 于是"用哪个密级过滤"只有一条路径，没有第二份判定。
   */
  @Transactional
  public List<SegmentSearchPort.Scored> preview(UUID appId, String rawToken, UUID requesterId) {
    ResolvedCredential resolved = resolver.resolveForAccess(rawToken, requesterId);
    if (!resolved.assistantId().equals(appId)) {
      // ★ 链接指向的是另一个应用 ⇒ 当作"不存在"：说清"这条链接属于别的应用"等于泄露一次归属关系
      throw new BusinessException(ErrorCode.NOT_FOUND, "访问凭证不存在", "它可能属于别的应用，或者已被删除");
    }
    Assistant app = requireVisibleApp(appId);
    if (app.kbIds().isEmpty()) {
      // 没有绑知识库 ⇒ 没有可检索的内容。返回空而不是报错：这是一种正常配置（先把链接发出去）
      return List.of();
    }
    ScopeFilter frozenScope =
        new ScopeFilter(
            resolved.subjectId(),
            DataScope.ALL,
            Set.of(),
            resolved.frozenClearance(),
            Set.of(),
            Set.of());
    return accessScope.asScope(
        frozenScope,
        requesterId,
        () -> {
          // ★ 可见的知识库仍然要过一遍判定（库可能被停用 / 软删 / 取消可见）——
          //   冻结密级只回答"多敏感的能看"，回答不了"哪些库还在"
          List<UUID> kbIds = segmentSearch.visibleKbIds(app.kbIds());
          if (kbIds.isEmpty()) {
            return List.of();
          }
          // ★★ 每次访问重跑一遍（不是复用创建时的结果）：判据 ③ 就落在这两行上
          return segmentSearch.visibleSegments(kbIds, resolved.frozenClearance(), PREVIEW_LIMIT);
        });
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 归属与可见性判定<strong>走应用</strong>（凭证自己不带 owner/visibility）。
   *
   * <p>★ 看不见的应用返 <strong>404</strong> 而不是 403（{@code 08 §4.4.3} 纪律 2）—— 403 等于告诉对方"这个 id 存在"。
   */
  private Assistant requireVisibleApp(UUID appId) {
    Assistant app =
        assistants
            .findById(appId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "应用不存在", "它可能已被删除，请刷新列表后重试"));
    if (!accessScope.canSee(app)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "应用不存在", "它可能已被删除，或者不在你的可见范围内");
    }
    return app;
  }

  /**
   * 凭证的<strong>读写</strong>只对应用的归属人开放。
   *
   * <p>★ 为什么不只看 {@code assistant:edit} 权限点（{@code 07 §4.1} 的口径）：权限点回答"这个动作能不能做"，
   * 数据范围回答"对<strong>哪一个</strong>能做"。一个能编辑应用的人不该能给别人名下的应用发链接 ——
   * 那等于<strong>以别人的名义</strong>对外承诺，而链接一旦发出就收不回来（撤销是终态，且已经泄露过一次）。
   */
  private void requireOwner(Assistant app, UUID requesterId) {
    if (requesterId == null || !requesterId.equals(app.ownerId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "无权限管理该应用的访问凭证", "只有应用的归属人可以创建、启停或撤销它的访问凭证");
    }
  }

  private AccessCredential requireCredentialOf(Assistant app, UUID id) {
    AccessCredential credential =
        credentials
            .findById(id)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "访问凭证不存在", "它可能已被删除，请刷新列表后重试"));
    if (!credential.assistantId().equals(app.id())) {
      // ★ 归属不匹配 ⇒ 404 而不是 403：说清"它属于别的应用"等于泄露一次归属关系
      throw new BusinessException(ErrorCode.NOT_FOUND, "访问凭证不存在", "它不属于这个应用");
    }
    return credential;
  }
}
