package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.identity.application.OrgService;
import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.infrastructure.AccessScopeAdapter;
import cn.lincoln.aiplatform.identity.infrastructure.JpaUserRepository;
import cn.lincoln.aiplatform.identity.infrastructure.JwtTokenService;
import cn.lincoln.aiplatform.knowledge.application.KnowledgeBaseService;
import cn.lincoln.aiplatform.knowledge.application.KnowledgeBaseView;
import cn.lincoln.aiplatform.knowledge.domain.ImportType;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.access.Subject;
import cn.lincoln.aiplatform.platform.access.SubjectKind;
import cn.lincoln.aiplatform.platform.access.Visibility;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★ 知识库共享的<strong>写入口</strong>（{@code PUT /knowledge-bases/:id/grants}，{@code 07 §8.1} / {@code
 * T302}）—— 真 PG + 真令牌 + 真 SQL。
 *
 * <h3>为什么这条账必须补上</h3>
 *
 * <p>{@code kb_knowledge_base_grant} 表、实体、{@code Grant} 判定、{@code ScopeFilter} 的 {@code ORG} 分支
 * 全都是现成的，唯独<strong>没有任何端点能把一条共享写进去</strong> ⇒ "共享给部门"这件事在产品上做不到 （{@code #37}
 * 收口缺的就是这一半）。本类同时守住写入口的**两道校验**与**可见性跟着授权走**。
 *
 * <h3>判据里的"人"必须是 dataScope = shared</h3>
 *
 * <p>种子里 {@code admin}/{@code zhangyun}/{@code liwei} 都是 {@code all}，而 {@code ScopeFilter.canSee} 在
 * {@code scope == ALL} 时<strong>直接返回 true</strong> ⇒ 用他们做夹具时"共享生效了吗"这条断言<strong>恒真</strong>。 所以这里用
 * {@code chenjun}（{@code USER} / {@code shared}），并且每次都断言它<strong>看不见</strong>别的部门共享的库。
 *
 * <p>★ {@code @Transactional} 是<strong>夹具要求</strong>、不是随手加的：{@code canSee} 要读 {@code KnowledgeBase}
 * 的懒加载集合 {@code grantRows}，而会话在方法返回后就关了 ⇒ 事务外调用会抛 {@code LazyInitializationException}（本类第一版就是这么红的）。
 * 产品路径不受影响：判定都发生在服务的 {@code @Transactional} 方法内部。夹具也顺带随事务回滚，不污染容器。
 */
@SpringBootTest
@Testcontainers
@Transactional
class KnowledgeBaseGrantWriteTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID ADMIN = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

  @Autowired KnowledgeBaseService kbService;
  @Autowired KnowledgeBaseRepository bases;
  @Autowired AccessScopeAdapter accessScope;
  @Autowired JwtTokenService tokenService;
  @Autowired JwtDecoder jwtDecoder;
  @Autowired OrgService orgs;
  @Autowired JpaUserRepository users;
  @Autowired RoleRepository roles;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  /** ★★ 判据 1（{@code 07 §10.5}）：共享给「技术部」⇒ 技术部<strong>及下级</strong>看得见，兄弟部门看不见。 */
  @Test
  void sharingToDepartmentIsVisibleToItsSubordinatesOnly() {
    UUID kb = createKb();
    UUID tech = orgId("TECH");

    KnowledgeBaseView view =
        asAdmin(() -> kbService.replaceGrants(kb, List.of(orgGrant(tech)), ADMIN));

    assertThat(view.visibility()).as("有授权 ⇒ 可见性是 SHARED").isEqualTo(Visibility.SHARED);
    assertThat(view.grants()).as("一条部门授权").hasSize(1);

    assertThat(scopeOf("chenjun", orgId("TECH_RD")).canSee(requireKb(kb), NOW))
        .as("研发组是技术部的下级 ⇒ 看得见（含下级）")
        .isTrue();
    assertThat(scopeOf("chenjun", orgId("MARKETING")).canSee(requireKb(kb), NOW))
        .as("★ 市场部看不见 —— 少了这一条，上面那条在 scope=all 的夹具下会恒真")
        .isFalse();
  }

  /** ★ 空清单 = 收回全部共享：可见性回到 {@code PRIVATE}，不留"SHARED 但一条授权都没有"的中间态。 */
  @Test
  void emptyGrantListTakesTheShareBack() {
    UUID kb = createKb();
    asAdmin(() -> kbService.replaceGrants(kb, List.of(orgGrant(orgId("TECH"))), ADMIN));

    KnowledgeBaseView after = asAdmin(() -> kbService.replaceGrants(kb, List.of(), ADMIN));

    assertThat(after.visibility()).isEqualTo(Visibility.PRIVATE);
    assertThat(after.grants()).isEmpty();
    assertThat(scopeOf("chenjun", orgId("TECH_RD")).canSee(requireKb(kb), NOW))
        .as("收回后连原来看得见的人也看不见了")
        .isFalse();
  }

  /**
   * ★ 主体不存在 ⇒ 400 + <strong>可读原因</strong>（中文类别名，不把机器码甩给用户）。
   *
   * <p>为什么这条不能省：写错一个 id 的授权<strong>不报错</strong>，而对方永远看不到这个库 —— 症状与"没共享过"一模一样。所以写入口必须拒绝它。
   */
  @Test
  void unknownSubjectIsRejectedWithAReadableReason() {
    UUID kb = createKb();

    assertThatThrownBy(
            () ->
                asAdmin(
                    () -> kbService.replaceGrants(kb, List.of(orgGrant(UUID.randomUUID())), ADMIN)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("共享主体不存在：部门")
        .extracting(e -> ((BusinessException) e).hint())
        .asString()
        .contains("对方永远看不到这个库");
  }

  /** ★ 每条授权至少要有一个动作：空动作的授权谁也不满足，只会让界面显示"已共享"。 */
  @Test
  void grantWithoutActionsIsRejected() {
    UUID kb = createKb();
    Grant noAction =
        new Grant(new Subject(SubjectKind.ORG, orgId("TECH").toString()), Set.of(), null, false);

    assertThatThrownBy(() -> asAdmin(() -> kbService.replaceGrants(kb, List.of(noAction), ADMIN)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("至少要有一个动作");
  }

  /**
   * ★ {@code 07 §10.5} 判据 3：<strong>停用一个部门 ⇒ 不产生新的共享，既有共享不变成不可见</strong>。
   *
   * <p>两个方向都断言，因为它们是同一句话的两半：只测"新的被拒"会漏掉"既有的也被误杀"。
   */
  @Test
  void disabledDepartmentTakesNoNewShareButKeepsTheExistingOne() {
    UUID shared = createKb();
    UUID fresh = createKb();
    UUID tech = orgId("TECH");
    asAdmin(() -> kbService.replaceGrants(shared, List.of(orgGrant(tech)), ADMIN));

    // ★ 走领域方法而不是 jdbc.update：后者绕过 JPA，一级缓存里还留着 enabled=true 的旧实体，exists 会读到它（本类踩过）
    orgs.update(tech, null, null, false, ADMIN);
    try {
      assertThatThrownBy(
              () -> asAdmin(() -> kbService.replaceGrants(fresh, List.of(orgGrant(tech)), ADMIN)))
          .isInstanceOf(BusinessException.class)
          .as("停用的部门不接受**新的**共享")
          .hasMessageContaining("共享主体不存在：部门");

      assertThat(scopeOf("chenjun", orgId("TECH_RD")).canSee(requireKb(shared), NOW))
          .as("★ 既有共享不因为部门停用而失效（停用 ≠ 撤销）")
          .isTrue();
    } finally {
      orgs.update(tech, null, null, true, ADMIN);
    }
  }

  /** 覆盖式：第二次写入顶掉第一次（不是叠加）—— 共享对话框说的就是"这份清单长这样"。 */
  @Test
  void grantsAreReplacedNotMerged() {
    UUID kb = createKb();
    asAdmin(() -> kbService.replaceGrants(kb, List.of(orgGrant(orgId("TECH"))), ADMIN));

    KnowledgeBaseView after =
        asAdmin(() -> kbService.replaceGrants(kb, List.of(orgGrant(orgId("MARKETING"))), ADMIN));

    assertThat(after.grants()).hasSize(1);
    assertThat(after.grants().getFirst().subject().id())
        .as("剩下的是第二次写进去的那个部门")
        .isEqualTo(orgId("MARKETING").toString());
    assertThat(scopeOf("chenjun", orgId("TECH_RD")).canSee(requireKb(kb), NOW)).isFalse();
    assertThat(scopeOf("chenjun", orgId("MARKETING")).canSee(requireKb(kb), NOW)).isTrue();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 把上下文切成"管理员在改"。
   *
   * <p>★ 写端点会先过一次<strong>可见性判定</strong>（看不见的库 404，与其它写端点同口径）， 所以测试必须在**已认证**的上下文里调用它们 ——
   * 第一次写这个类时忘了这一步，六条用例里有六条 报的是「知识库不存在」，而那与被测行为无关（是夹具在裸跑）。
   */
  private <T> T asAdmin(Supplier<T> body) {
    SecurityContext previous = SecurityContextHolder.getContext();
    try {
      Jwt jwt =
          Jwt.withTokenValue("test-token")
              .header("alg", "none")
              .subject(ADMIN.toString())
              .claim("role", "ADMIN")
              // scope=ALL：管理员本来就看得见所有库（本类要测的是授权写入与部门判定，不是数据范围）
              .claim("scope", "ALL")
              .claim("clearance", "CONFIDENTIAL")
              .build();
      JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
      authentication.setAuthenticated(true);
      SecurityContextHolder.getContext().setAuthentication(authentication);
      return body.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  private UUID createKb() {
    return bases
        .save(
            new KnowledgeBase(
                UUID.randomUUID(),
                "共享写入口-" + UUID.randomUUID().toString().substring(0, 8),
                "制度规范",
                "共享写入口夹具",
                "📘",
                ImportType.LOCAL,
                UUID.randomUUID(),
                ClassificationLevel.SECRET,
                ADMIN,
                null,
                ADMIN))
        .id();
  }

  private KnowledgeBase requireKb(UUID id) {
    return bases.findById(id).orElseThrow();
  }

  private static Grant orgGrant(UUID orgId) {
    return new Grant(
        new Subject(SubjectKind.ORG, orgId.toString()), Set.of("knowledge:view"), null, false);
  }

  private UUID orgId(String code) {
    return jdbc.queryForObject("select id from iam_org where code = ?", UUID.class, code);
  }

  /**
   * 真账号 + 真角色 + 真签名 + 真验签，只把部门换成参数给的那个（{@code null} = 未归类）。
   *
   * <p>★ {@code setAuthenticated(true)} 是必须的（Spring Security 6 不再由构造器代设），否则适配器 第一句话就判"未认证 ⇒
   * nobody"，本类会整体变成"什么都看不见"。
   */
  private ScopeFilter scopeOf(String username, UUID orgId) {
    User user = users.findByUsernameAndDeletedAtIsNull(username).orElseThrow();
    user.assignOrg(orgId);
    Role role = roles.findById(user.roleId()).orElseThrow();
    Jwt jwt = jwtDecoder.decode(tokenService.issueAccessToken(user, role, List.of()));
    JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
    authentication.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    return accessScope.currentScope();
  }
}
