package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.AccessCredential;
import cn.lincoln.aiplatform.assistant.domain.AccessCredentialRepository;
import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.access.PublicTokenResolver;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用级公开令牌的解析实现（端口在 {@code platform}、实现在提供方模块 {@code assistant} —— {@code 08 §4.2.3}）。
 *
 * <h3>★ 它同时是"按 token 的解析缓存 + 撤销时的失效钩子"</h3>
 *
 * <p>公开面是<strong>免登录</strong>的：每打开一次链接都要按 token 查一次凭证、再查一次它指向的应用。 那两次查询对同一个 token
 * 是完全可重复的（凭证的属性只由管理员改），于是按 token 缓存掉它。
 *
 * <p>★★ <strong>判据 ① 就落在 {@link #forget} 上</strong>：撤销（{@code AccessCredentialService.revoke}）
 * 与停用必须<strong>当场</strong>把它清掉。清不掉的症状是"撤销了还能用一段时间" ——
 * 而"撤销"对使用者的含义是<strong>现在就不能用了</strong>。判据的原始措辞是"在同一个请求周期内再访问即被拒"， 所以这条链上不允许有"等它失效"的环节。
 *
 * <h3>★ 缓存的是"解析结果"，不是"看得见什么"</h3>
 *
 * <p>缓存 {@link ResolvedCredential}（几个不可变的值），<strong>不缓存</strong>这次的可见集合： 后者每次访问都必须重算（{@code 13
 * §5.2} 第 ② 级）。把内容也缓存起来，就是那条判据明确禁止的写法 —— 也是本交付报告里金丝雀要破坏的那一处。
 *
 * <h3>★ 一致性：单实例假设（这是一笔要写下来的账）</h3>
 *
 * <p>缓存只在一个 JVM 里生效（模块化单体、单实例部署）。多实例部署时，撤销发生在实例 A、解析发生在 实例 B ⇒ B
 * 上的旧项<strong>不会被清掉</strong>。届时正确的做法是一次广播失效（或把读路径改成 每次查库 + 短 TTL），而<strong>不是</strong>把 TTL 调小 ——
 * 判据 ① 是"立即"，不是"很快"。
 */
@Component
public class PublicCredentialResolver implements PublicTokenResolver {

  private static final Logger log = LoggerFactory.getLogger(PublicCredentialResolver.class);

  private final AssistantRepository assistants;
  private final AccessCredentialRepository credentials;
  private final ConcurrentHashMap<String, ResolvedCredential> cache = new ConcurrentHashMap<>();

  public PublicCredentialResolver(
      AssistantRepository assistants, AccessCredentialRepository credentials) {
    this.assistants = assistants;
    this.credentials = credentials;
  }

  /**
   * {@inheritDoc}
   *
   * <p>★ 只回答<strong>状态</strong>问题（不存在 / 已撤销 / 已过期 / 已停用）； "解析它的人够不够格"<strong>不在这里</strong>判 —— 那是
   * {@code AccessScopePort.asScope} 的事（密级取更低、其余分量照抄请求者）。 把权限收窄散成两处，就是"同一判定有两个实现"的开始。
   *
   * <p>★ 不认识的令牌（不是 {@code pk_live_} 前缀）返回 {@link Optional#empty()}，交给别的实现 —— 那正是 {@code 08 §4.2.3}
   * 要的"一个入口、多个实现"。
   */
  @Override
  public Optional<PublicAccess> resolve(String rawToken, UUID requesterId) {
    if (rawToken == null || !rawToken.startsWith(AccessCredential.TOKEN_PREFIX)) {
      return Optional.empty();
    }
    ResolvedCredential resolved = resolveForAccess(rawToken, requesterId);
    return Optional.of(
        new PublicAccess(
            resolved.credentialId(),
            resolved.assistantId(),
            resolved.subjectId(),
            resolved.frozenClearance()));
  }

  /**
   * 解析并记一次访问（服务层与公开面共用这一条路径）。
   *
   * @throws BusinessException 不存在 / 已撤销 / 已过期 / 已停用 —— 四种状态各带各的文案
   */
  @Transactional
  public ResolvedCredential resolveForAccess(String rawToken, UUID requesterId) {
    ResolvedCredential cached = cache.get(rawToken);
    if (cached != null) {
      // ★ 缓存也要"复判一次状态"：撤销/停用走 forget() 立刻清（判据①），
      //   而**过期**是时间自己走过去的 —— 没有任何一次写操作会触发清缓存。
      //   少了这一句，一条过期的链接会一直可用，直到缓存项因别的原因消失。
      AccessCredential credential = credentials.findByToken(rawToken).orElse(null);
      requireUsable(credential, rawToken);
      recordAccess(credential);
      return cached;
    }

    AccessCredential credential =
        credentials
            .findByToken(rawToken)
            .orElseThrow(() -> notFound("确认链接是否完整；已删除的凭证不会因为旧链接还在而恢复"));
    requireUsable(credential, rawToken);

    Assistant app =
        assistants.findById(credential.assistantId()).orElseThrow(() -> notFound("它指向的应用已被删除"));

    ResolvedCredential resolved =
        new ResolvedCredential(
            credential.id(), app.id(), app.ownerId(), credential.maxClassification());
    cache.put(rawToken, resolved);
    recordAccess(credential);
    log.debug("解析公开令牌成功：凭证 {} → 应用 {}", resolved.credentialId(), resolved.assistantId());
    return resolved;
  }

  /**
   * ★★ 丢掉一个 token 的解析结果（撤销 / 停用时调用）—— 判据 ① 的载体。
   *
   * <p>幂等：清一个不存在的项不算错 —— 否则撤销会变成一条"可能失败"的动作。
   */
  public void forget(String token) {
    if (token == null) {
      return;
    }
    ResolvedCredential removed = cache.remove(token);
    if (removed != null) {
      log.debug("已清掉访问凭证 {} 的解析缓存（撤销/停用 ⇒ 立即失效）", removed.credentialId());
    }
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 状态复判（<strong>缓存命中时也走它</strong>）。
   *
   * <p>四种状态各带各的文案与码，理由见 {@code AccessCredentialService} 的类注释。
   */
  private void requireUsable(AccessCredential credential, String rawToken) {
    if (credential == null) {
      throw notFound("确认链接是否完整；已删除的凭证不会因为旧链接还在而恢复");
    }
    if (credential.revokedAt() != null) {
      // ★ 已经撤销了，缓存里那一项就是错的 ⇒ 顺手清掉（防御性：正常路径上 revoke 已经清了）
      forget(rawToken);
      throw new BusinessException(ErrorCode.FORBIDDEN, "访问凭证已撤销", "撤销是终态，不能重新启用；请在凭证管理里重新生成一条");
    }
    if (credential.expiresAt() != null && !credential.expiresAt().isAfter(Instant.now())) {
      forget(rawToken);
      throw new BusinessException(
          ErrorCode.FORBIDDEN,
          "访问凭证已过期（有效期至 " + credential.expiresAt() + "）",
          "请分享者重新生成一条，或把有效期设为长期有效");
    }
    if (!credential.enabled()) {
      forget(rawToken);
      throw new BusinessException(ErrorCode.FORBIDDEN, "访问凭证已停用", "停用是可恢复的：请分享者在凭证管理里重新启用它");
    }
  }

  /** 记一次访问（契约 {@code accessCount} / {@code lastAccessAt}）。 */
  private void recordAccess(AccessCredential credential) {
    credential.recordAccess();
    credentials.save(credential);
  }

  private static BusinessException notFound(String hint) {
    return new BusinessException(ErrorCode.NOT_FOUND, "访问凭证不存在", hint);
  }
}
