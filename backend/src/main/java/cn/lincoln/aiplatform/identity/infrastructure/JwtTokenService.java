package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Permissions;
import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.User;
import java.time.Instant;
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

  /** 给一个用户签发访问令牌。 */
  public String issueAccessToken(User user, Role role) {
    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(user.id().toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.accessTtl()))
            .claim("username", user.username())
            // 角色代码：前端与网关都可能要读（也让"这个令牌属于什么角色"在排障时一眼可见）
            .claim("role", role.code())
            // ★ 权限点数组 —— SecurityConfig 的 JwtAuthenticationConverter 会把它翻成 GrantedAuthority，
            //   于是 @PreAuthorize("hasAuthority('knowledge:create')") 直接用得上
            .claim("authorities", Permissions.of(role.code()))
            // ★ 数据范围与密级也进令牌（与权限点同样的取舍）：省掉"每个请求查一次角色表"，
            //   代价同样是"改了角色的范围/密级，最长 accessTtl 才生效"。
            //   AccessScopeAdapter 因此可以零查询地构造 ScopeFilter。
            .claim("scope", role.dataScope().name())
            .claim("clearance", role.maxClassification().name())
            .claim("clLabels", role.labels())
            .build();

    return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
  }

  /** 访问令牌的有效期（秒）—— 登录响应要把它回给前端，避免前端另配一份常量。 */
  public long accessTokenTtlSeconds() {
    return properties.accessTtl().toSeconds();
  }

  public JwtProperties properties() {
    return properties;
  }
}
