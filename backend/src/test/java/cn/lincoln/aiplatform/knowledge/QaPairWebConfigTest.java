package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cn.lincoln.aiplatform.knowledge.application.QaPairService;
import cn.lincoln.aiplatform.knowledge.application.QaPairView;
import cn.lincoln.aiplatform.knowledge.domain.QaPair;
import cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin;
import cn.lincoln.aiplatform.knowledge.domain.QaPairRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ 问答对 web 入口的判据（需求 {@code 03 §六 FR-03-12}）。
 *
 * <h3>为什么这批要单独立判据</h3>
 *
 * <p>在本批之前，`kb_qa_pair` 只有「导入 / 列出」两个服务层方法，**没有任何 HTTP 入口** （知识库页 TABS 只有 文档/分段/命中测试/设置）⇒ 用户在 web
 * 上**根本配不了问答对**。 本批补的是「建 / 改名 / 删」三个入口。
 *
 * <h3>覆盖六条（每条对应一种写错的形状）</h3>
 *
 * <ol>
 *   <li><b>建出来的行真落库</b>且来源固定 {@code MANUAL} —— 让调用方传来源等于允许"人手写的一条自称来自会话"，那会让追溯失效；
 *   <li>★ <b>密级高于库上限 ⇒ 400</b>（`13 §6` 的 ceiling）—— 不拦就是一次**静默越权**；
 *   <li>★ <b>改名只改问题</b>：答案与密级一动不动（否则"改个错字"能顺手把密级改低）；
 *   <li>★ <b>删除是软删</b>：行还在、{@code deleted_at} 有值 —— 硬删会丢掉命中计数与来源追溯；
 *   <li>★ <b>删除幂等</b>：删两次不报错、且 {@code deleted_at} 不被刷新；
 *   <li>★ <b>别人的库 ⇒ 404</b>（不是 403）：403 等于确认了 id 的存在性。
 * </ol>
 */
@SpringBootTest
@Testcontainers
class QaPairWebConfigTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID OWNER = UUID.fromString("30000000-0000-4000-8000-0000000000aa");
  private static final UUID STRANGER = UUID.fromString("30000000-0000-4000-8000-0000000000bb");

  @Autowired QaPairService qaPairs;

  @Autowired QaPairRepository pairs;

  @Autowired JdbcTemplate jdbc;

  @Test
  void createdPairIsPersistedWithManualOrigin() {
    UUID kb = seedKb(OWNER, "INTERNAL");

    QaPairView created =
        as(
            OWNER,
            () ->
                qaPairs.create(
                    kb, "出差住宿费上限是多少", "一晚不超过 600 元", ClassificationLevel.INTERNAL, null, OWNER));

    QaPair reloaded =
        pairs
            .findById(UUID.fromString(created.id()))
            .orElseThrow(() -> new AssertionError("★ 必须真的落库 —— 只看返回值的话，一个『改了内存没落库』的实现会完全通过"));
    assertThat(reloaded.question()).isEqualTo("出差住宿费上限是多少");
    assertThat(reloaded.answer()).isEqualTo("一晚不超过 600 元");
    assertThat(reloaded.origin())
        .as("★★ 从 web 手输的一律 MANUAL —— 让调用方传来源等于允许『人手写的一条自称来自会话』，追溯就失效了")
        .isEqualTo(QaPairOrigin.MANUAL);
    assertThat(reloaded.kbId()).as("★ 归属 = 知识库（问答对没有独立可见性，见 V31 注释）").isEqualTo(kb);
  }

  @Test
  void classificationAboveTheKbCeilingIsRejected() {
    UUID kb = seedKb(OWNER, "INTERNAL");

    BusinessException error =
        catchThrowableOfType(
            () ->
                as(
                    OWNER,
                    () -> qaPairs.create(kb, "问题", "答案", ClassificationLevel.SECRET, null, OWNER)),
            BusinessException.class);

    assertThat(error).as("★★ 密级高于库上限必须被拦 —— 不拦就是一次**静默越权**（13 §6）").isNotNull();
    assertThat(error.code()).isEqualTo(ErrorCode.BAD_REQUEST);
    assertThat(error.getMessage()).as("★ 原因要写清是哪一档超了哪一档").contains("SECRET").contains("INTERNAL");
    assertThat(pairs.countByKb(kb)).as("★ 被拦下的那条不许留下半条记录").isZero();
  }

  @Test
  void renameTouchesOnlyTheQuestion() {
    UUID kb = seedKb(OWNER, "INTERNAL");
    QaPairView created =
        as(
            OWNER,
            () -> qaPairs.create(kb, "旧问题", "答案不变", ClassificationLevel.INTERNAL, null, OWNER));

    as(OWNER, () -> qaPairs.rename(UUID.fromString(created.id()), "新问题", OWNER));

    QaPair reloaded = pairs.findById(UUID.fromString(created.id())).orElseThrow();
    assertThat(reloaded.question()).isEqualTo("新问题");
    assertThat(reloaded.answer())
        .as("★★ 改名只改问题 —— 否则『只想改个错字』能顺手把答案或密级改掉，而那种改动没有任何地方会报错")
        .isEqualTo("答案不变");
    assertThat(reloaded.classification()).isEqualTo(ClassificationLevel.INTERNAL);
  }

  @Test
  void deleteIsSoftAndIdempotent() {
    UUID kb = seedKb(OWNER, "INTERNAL");
    QaPairView created =
        as(
            OWNER,
            () -> qaPairs.create(kb, "要删的问题", "答案", ClassificationLevel.INTERNAL, null, OWNER));
    UUID id = UUID.fromString(created.id());

    runAs(OWNER, () -> qaPairs.delete(id, OWNER));
    Object firstDeletedAt =
        jdbc.queryForObject("select deleted_at from kb_qa_pair where id = ?", Object.class, id);
    assertThat(firstDeletedAt)
        .as("★★ 必须是**软删**：硬删会丢掉命中计数与来源追溯（source_ref），而那是『这套机制值不值』的读数")
        .isNotNull();
    assertThat(jdbc.queryForObject("select count(*) from kb_qa_pair where id = ?", Long.class, id))
        .as("★ 行还在（软删）")
        .isEqualTo(1L);

    runAs(OWNER, () -> qaPairs.delete(id, OWNER));
    Object secondDeletedAt =
        jdbc.queryForObject("select deleted_at from kb_qa_pair where id = ?", Object.class, id);
    assertThat(secondDeletedAt)
        .as("★ 幂等：删两次不报错、且 deleted_at 不被刷新（否则『什么时候被删的』说不清）")
        .isEqualTo(firstDeletedAt);
  }

  @Test
  void someoneElsesKnowledgeBaseIs404() {
    UUID kb = seedKb(OWNER, "INTERNAL");
    QaPairView created =
        as(
            OWNER,
            () -> qaPairs.create(kb, "别人的问题", "答案", ClassificationLevel.INTERNAL, null, OWNER));
    UUID id = UUID.fromString(created.id());

    BusinessException onRename =
        catchThrowableOfType(
            () -> asOwnScope(STRANGER, () -> qaPairs.rename(id, "改名试试", STRANGER)),
            BusinessException.class);
    assertThat(onRename).isNotNull();
    assertThat(onRename.code())
        .as("★★ 必须 404 而不是 403 —— 403 等于告诉对方『这个 id 是存在的』（与 DocumentService / SegmentService 同口径）")
        .isEqualTo(ErrorCode.NOT_FOUND);

    BusinessException onDelete =
        catchThrowableOfType(
            () ->
                asOwnScope(
                    STRANGER,
                    () -> {
                      qaPairs.delete(id, STRANGER);
                      return null;
                    }),
            BusinessException.class);
    assertThat(onDelete).isNotNull();
    assertThat(onDelete.code()).isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(pairs.findById(id).orElseThrow().deleted()).as("★ 别人删不动：行必须仍然可见").isFalse();
  }

  @Test
  void listReturnsWhatWasCreated() {
    UUID kb = seedKb(OWNER, "INTERNAL");
    as(OWNER, () -> qaPairs.create(kb, "问题一", "答案一", ClassificationLevel.INTERNAL, null, OWNER));
    as(OWNER, () -> qaPairs.create(kb, "问题二", "答案二", ClassificationLevel.INTERNAL, null, OWNER));

    PageResult<QaPairView> listed = as(OWNER, () -> qaPairs.list(kb, 1, 20));

    assertThat(listed.total()).as("★ total 是**过滤后**的数（与页内条数同口径）").isEqualTo(2L);
    assertThat(listed.list())
        .extracting(QaPairView::question)
        .containsExactlyInAnyOrder("问题一", "问题二");
  }

  // ────────────────────────── 工具 ──────────────────────────

  /**
   * 以某个用户的身份执行（★ 必须：服务层第一步就是按 `accessScope` 判库的可见性， 而那是 ThreadLocal —— 测试线程上没有 SecurityContext 时是
   * `nobody()` ⇒ 一律 404）。
   *
   * @param user 请求者
   * @param body 要执行的动作
   * @return 动作的返回值
   */
  private <T> T as(UUID user, Supplier<T> body) {
    return SecurityContexts.asUser(user, ClassificationLevel.CONFIDENTIAL, body);
  }

  /**
   * 同上，但用于没有返回值／必然抛异常的动作。
   *
   * @param user 请求者
   * @param body 要执行的动作
   */
  private void runAs(UUID user, Runnable body) {
    as(
        user,
        () -> {
          body.run();
          return null;
        });
  }

  /**
   * 以某个用户身份、**数据范围 = OWN** 执行。
   *
   * <p>★★ 为什么要自己造令牌：{@code SecurityContexts.asUser} 把 {@code scope} 写死成 {@code ALL}
   * （它自己的注释也写明"本辅助只管密级这一维，要验范围的测试自己造令牌"）。 而 {@code ALL} 在 {@code ScopeFilter#canSee} 的**第二步就短路返回
   * true** ⇒ 用它测"别人的库看不见"会**恒假**（实测：跑出来是 200 而不是 404）。
   *
   * <p>★ 这条也解释了第一次跑的三条红：那是**判据的构造错了**，不是被测逻辑错了。
   *
   * @param user 请求者
   * @param body 要执行的动作
   * @return 动作的返回值
   */
  private <T> T asOwnScope(UUID user, Supplier<T> body) {
    var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
    try {
      var jwt =
          org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token-own")
              .header("alg", "none")
              .subject(user.toString())
              .claim("role", "ADMIN")
              .claim("scope", "OWN")
              .claim("clearance", ClassificationLevel.CONFIDENTIAL.name())
              .build();
      var authentication =
          new org.springframework.security.oauth2.server.resource.authentication
              .JwtAuthenticationToken(jwt);
      // ★ 与 SecurityContexts 里同一条坑：构造器不再代设 authenticated，
      //   而 currentJwt() 第一句就是 `!isAuthenticated() → nobody()`
      authentication.setAuthenticated(true);
      var context =
          org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authentication);
      org.springframework.security.core.context.SecurityContextHolder.setContext(context);
      return body.get();
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
    }
  }

  /** 直接建知识库（走 JDBC：本类只关心问答对，不重复 KnowledgeBaseService 的建库校验）。 */
  private UUID seedKb(UUID owner, String maxClassification) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, ?, '制度规范', 'LOCAL', ?, ?, ?, 'PRIVATE', now(), now())",
        id,
        "问答对判据库",
        UUID.fromString("30000000-0000-4000-8000-0000000000ee"),
        maxClassification,
        owner);
    return id;
  }
}
