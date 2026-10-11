package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * 访问令牌的签发。
 *
 * <p>★ <strong>权限点放在令牌里</strong>（{@code authorities} 声明），而不是每次请求回查数据库。
 * 这笔账是这样算的：省掉的是"每个请求一次角色/权限查询"，付出的是 <em>"改了权限之后，最长 {@code accessTtl} 才会生效"</em>。 本项目里 30
 * 分钟是可以接受的（权限变更不是秒级敏感操作）， 而每请求一次查询是<strong>每一次</strong>都要付的。若将来不能接受， 正确的做法是加一个"令牌版本号 /
 * 权限版本"声明并在改动时递增，而不是退回每请求查库。
 *
 * <p>★ 权限点由<strong>调用方传入</strong>，而不是这里查 {@code Permissions.of(roleCode)}： 「角色挂哪些权限点」的真源是库（{@code
 * iam_role_permission}，见 {@code RolePermissionRepository}）。
 * 若在这里按角色代码反查常量，管理员在界面上勾的授权会<strong>静默失效</strong> —— 能存、能回显，但鉴权不理会。
 *
 * <p>★ 这里为什么不做成接口：签发是模块内的实现细节，没有第二个实现者。跨模块的隔离由 Modulith 保证（{@code identity} 对外只暴露 {@code api}
 * 包），此处再抽一层接口是"为一个 实现者建抽象"。真出现第二种签发方式（例如接入外部 IdP）时，抽接口是个纯机械动作。
 */
@Component
public class JwtTokenService {

  private final JwtEncoder encoder;
  private final JwtProperties properties;

  public JwtTokenService(JwtEncoder encoder, JwtProperties properties) {
    this.encoder = encoder;
    this.properties = properties;
  }

  /**
   * 给一个用户签发访问令牌。
   *
   * @param authorities 该角色当前持有的权限点（来自 {@code iam_role_permission}，不是代码常量）
   */
  public String issueAccessToken(User user, Role role, Collection<String> authorities) {
    Instant now = Instant.now();

    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(user.id().toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTtl()))
            .claim("username", user.username())
            // 角色代码：前端与网关都可能要读（也让"这个令牌属于什么角色"在排障时一眼可见）
            .claim("role", role.code())
            // ★ 权限点数组 —— SecurityConfig 的 JwtAuthenticationConverter 会把它翻成 GrantedAuthority，
            //   于是 @PreAuthorize("hasAuthority('knowledge:create')") 直接用得上。
            //   这份集合是库里读出来的，不是 Permissions.of（见类注释）。
            .claim("authorities", List.copyOf(authorities))
            // ★ 数据范围与密级也进令牌（与权限点同样的取舍）：省掉"每个请求查一次角色表"，
            //   代价同样是"改了角色的范围/密级，最长 accessTtl 才生效"。
            //   AccessScopeAdapter 因此可以零查询地构造 ScopeFilter。
            .claim("scope", role.dataScope().name())
            .claim("clearance", role.maxClassification().name())
            .claim("clLabels", role.labels());

    // ★ 所属部门（`07 §十一` 消费位③）：只放**我这一个**部门 id，**不**放展开后的子树。
    //   为什么不在签发时展开成"我的部门及下级"那个集合：
    //     ① 子树可能很大（一个分公司下面上百个部门）⇒ 令牌体积与"部门树的形状"耦合；
    //     ② 展开结果会被冻结到令牌过期 ⇒ 上级部门新增一个下级、或我被调岗，最长 accessTtl 才生效。
    //   代价是判定侧每请求一次前缀查询（AccessScopeAdapter 里按请求缓存，不是每次 canSee 都查）。
    //   没有部门时**不发这个声明** —— 判定侧读不到 ⇒ 空集 ⇒ 看不见任何部门共享（fail-closed）。
    if (user.orgId() != null) {
      claims.claim("orgId", user.orgId().toString());
    }

    return encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
  }

  /** 访问令牌的有效期（秒）—— 登录响应要把它回给前端，避免前端另配一份常量。 */
  public long accessTokenTtlSeconds() {
    return properties.accessTtl().toSeconds();
  }

  public JwtProperties properties() {
    return properties;
  }
}
