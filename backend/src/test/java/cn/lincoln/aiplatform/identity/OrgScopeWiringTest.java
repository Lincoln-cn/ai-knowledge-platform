package cn.lincoln.aiplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.infrastructure.AccessScopeAdapter;
import cn.lincoln.aiplatform.identity.infrastructure.JpaUserRepository;
import cn.lincoln.aiplatform.identity.infrastructure.JwtTokenService;
import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.access.Subject;
import cn.lincoln.aiplatform.platform.access.SubjectKind;
import cn.lincoln.aiplatform.platform.access.Visibility;
import cn.lincoln.aiplatform.platform.org.OrgDirectoryPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★ 部门共享这条链的<strong>真栈贯穿</strong>（{@code 07 §十一} 消费位③ / {@code T301} 后半 / Issue #38）： V28 种子 → JPA
 * 映射 → 签发令牌 → 验签 → 适配器 → <strong>真 SQL 祖先查询</strong> → {@code ScopeFilter} → {@code canSee}。
 *
 * <h3>为什么有了桩版本（{@code AccessScopeOrgScopeTest}）还要这一个</h3>
 *
 * <p>桩版本验的是"适配器有没有把端口的结果放进范围"，而<strong>端口背后那句 SQL 一次都没跑过</strong>。 本项目已经栽过同类：{@code
 * JpaUserRepository} 的 {@code :keyword} 在 HQL 里没有类型信息 ⇒ 冷启动第一次 PG 按 {@code bytea} 发送 ⇒ {@code
 * lower(bytea)} 不存在 ⇒ 500，而测试在别的执行顺序下全绿（那条注释还在那个文件里）。 所以方向、前缀、参数绑定这三件事，只有真库能答。
 *
 * <h3>★ 夹具的判别力（这一条比读数更重要）</h3>
 *
 * <p>判据里那个"人"必须 <strong>{@code dataScope = shared}</strong>：V4/V6 的四个演示账号里 admin/{@code
 * zhangyun}/{@code liwei} 都是 <strong>{@code all}</strong> —— 而 {@code ScopeFilter.canSee} 在 {@code
 * scope == ALL} 时<strong>直接返回 true</strong>，
 * 于是"共享给部门看得见"这条断言<strong>恒真</strong>，测不出任何东西（本项目把"恒真的断言"当缺陷）。 所以这里用 {@code chenjun}（{@code
 * USER}，{@code shared}），并且每次都断言它<strong>看不见**别的**部门</strong>共享的东西。
 */
@SpringBootTest
@Testcontainers
class OrgScopeWiringTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID SOMEONE_ELSE = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

  @Autowired OrgDirectoryPort orgDirectory;
  @Autowired AccessScopeAdapter accessScope;
  @Autowired JwtTokenService tokenService;
  @Autowired JwtDecoder jwtDecoder;
  @Autowired JpaUserRepository users;
  @Autowired RoleRepository roles;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  /** V28 的树是<strong>三级</strong>的，而祖先链查询必须真的走出"下级 → 上级 → 根"三步。 */
  @Test
  void realQueryWalksTheSeededThreeLevelTree() {
    UUID techRd = orgId("TECH_RD");
    UUID tech = orgId("TECH");
    UUID root = orgId("ROOT");

    assertThat(orgDirectory.ancestorsAndSelf(techRd))
        .as("研发组 → 技术部 → 总部。少一层就等于把'共享给上级部门'变成看不见")
        .containsExactlyInAnyOrder(techRd, tech, root);
    assertThat(orgDirectory.ancestorsAndSelf(tech)).containsExactlyInAnyOrder(tech, root);
    assertThat(orgDirectory.ancestorsAndSelf(root)).as("根只有自己").containsExactly(root);
    assertThat(orgDirectory.ancestorsAndSelf(UUID.randomUUID())).as("不存在的部门 ⇒ 空（不是全表）").isEmpty();
  }

  /**
   * 前缀匹配不许把"字符串前缀相同、但不是祖先"的部门拉进来。
   *
   * <p>造一个 {@code TECH2}（路径 {@code /ROOT/TECH2/}）：它是我 {@code /ROOT/TECH/TECH_RD/} 的**字符串**前缀邻居，
   * 但不是我路径上的任何一级。
   */
  @Test
  void realQueryDoesNotTreatStringPrefixNeighboursAsAncestors() {
    UUID probe = UUID.randomUUID();
    UUID root = orgId("ROOT");
    String rootPath =
        jdbc.queryForObject("select path from iam_org where id = ?", String.class, root);
    jdbc.update(
        "insert into iam_org (id, parent_id, code, name, path, sort, enabled, created_at,"
            + " updated_at) values (?, ?, 'TECH2', '技术二部', ?, 20, true, now(), now())",
        probe,
        root,
        rootPath + "TECH2/");

    try {
      List<UUID> ancestors = orgDirectory.ancestorsAndSelf(orgId("TECH_RD"));

      assertThat(ancestors).as("真祖先在").contains(orgId("TECH"), root);
      assertThat(ancestors)
          .as("★ `/ROOT/TECH2/` 不是 `/ROOT/TECH/TECH_RD/` 的祖先 —— 前缀相同不等于在同一支上")
          .doesNotContain(probe);
    } finally {
      jdbc.update("delete from iam_org where id = ?", probe);
    }
  }

  /**
   * ★★ 判据 1（{@code 07 §10.5}）：共享给「技术部」⇒ 技术部<strong>及下级</strong>的人看得见，兄弟部门看不见。
   *
   * <p>用的person = {@code chenjun}（{@code dataScope=shared}，所以范围判定真的起作用）， 部门在**内存里**改成研发组：本类要的是"一个
   * shared 范围且属于技术部下级的人"，而种子里没有这样的人 —— 不动库是刻意的（共用容器里改种子会污染别的用例）。
   */
  @Test
  void subordinateSeesLibrarySharedToParentDepartmentButNotToSiblings() {
    UUID tech = orgId("TECH");
    UUID marketing = orgId("MARKETING");

    ScopeFilter inSubordinate = scopeOf("chenjun", orgId("TECH_RD"));

    assertThat(inSubordinate.myOrgIds())
        .as("研发组的人 ⇒ 祖先链含技术部与总部")
        .containsExactlyInAnyOrder(
            orgId("TECH_RD").toString(), tech.toString(), orgId("ROOT").toString());
    assertThat(inSubordinate.canSee(sharedWithOrg(tech), NOW))
        .as("共享给技术部 ⇒ 研发组的人看得见（07 §10.5 判据 1）")
        .isTrue();
    assertThat(inSubordinate.canSee(sharedWithOrg(marketing), NOW))
        .as("★ 兄弟部门看不见 —— 少了这一条，上面那条在 scope=all 的夹具下会恒真")
        .isFalse();

    ScopeFilter inSibling = scopeOf("chenjun", marketing);
    assertThat(inSibling.canSee(sharedWithOrg(tech), NOW)).as("反过来：市场部的人看不见共享给技术部的库").isFalse();
  }

  /** ★ 未归类（{@code org_id} 为空）⇒ 空集 ⇒ 看不见任何部门共享（fail-closed）。 */
  @Test
  void userWithoutDepartmentSeesNoDepartmentShare() {
    ScopeFilter scope = scopeOf("chenjun", null);

    assertThat(scope.myOrgIds()).isEmpty();
    assertThat(scope.canSee(sharedWithOrg(orgId("TECH")), NOW)).isFalse();
    assertThat(scope.canSee(sharedWithOrg(orgId("MARKETING")), NOW)).isFalse();
  }

  /** ★ 种子给演示账号的归属要能通过 <strong>JPA 映射</strong>读出来 —— 读不出来，令牌里就不会有它。 */
  @Test
  void seededDepartmentAssignmentIsVisibleThroughTheJpaMapping() {
    assertThat(users.findByUsernameAndDeletedAtIsNull("liwei").orElseThrow().orgId())
        .as("V28 把 liwei 归到研发组（技术部的下级）")
        .isEqualTo(orgId("TECH_RD"));
    assertThat(users.findByUsernameAndDeletedAtIsNull("chenjun").orElseThrow().orgId())
        .as("chenjun 归到市场部（对照组：兄弟部门）")
        .isEqualTo(orgId("MARKETING"));
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 真账号 + 真角色 + 真签名 + 真验签，只把部门换成参数给的那个（{@code null} = 取消归类）。
   *
   * <p>★ {@code setAuthenticated(true)} 是必须的：Spring Security 6 里 {@code new
   * JwtAuthenticationToken(jwt)} 之后 它仍是 {@code false}，而适配器第一句话就是"未认证 ⇒ nobody"——漏了它本类会整体变成"什么都看不见"。
   */
  private ScopeFilter scopeOf(String username, UUID orgId) {
    User user = users.findByUsernameAndDeletedAtIsNull(username).orElseThrow();
    user.assignOrg(orgId);
    Role role = roles.findById(user.roleId()).orElseThrow();
    Jwt jwt = jwtDecoder.decode(tokenService.issueAccessToken(user, role, List.of()));

    assertThat(jwt.getClaimAsString("orgId"))
        .as("令牌里必须先有 orgId，后面的判定才有材料（这正是 #38 缺的那一半）")
        .isEqualTo(orgId == null ? null : orgId.toString());

    JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
    authentication.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    return accessScope.currentScope();
  }

  private UUID orgId(String code) {
    return jdbc.queryForObject("select id from iam_org where code = ?", UUID.class, code);
  }

  private static OwnedResource sharedWithOrg(UUID orgId) {
    Grant grant =
        new Grant(
            new Subject(SubjectKind.ORG, orgId.toString()), Set.of("knowledge:view"), null, false);
    return new OwnedResource() {
      @Override
      public UUID ownerId() {
        return SOMEONE_ELSE;
      }

      @Override
      public Visibility visibility() {
        return Visibility.SHARED;
      }

      @Override
      public List<Grant> grants() {
        return List.of(grant);
      }
    };
  }
}
