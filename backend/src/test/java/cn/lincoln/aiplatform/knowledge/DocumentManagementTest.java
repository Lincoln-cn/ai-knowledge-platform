package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 文档与分段的<strong>管理动作</strong>端到端（0.3.1）—— 改名 / 启停 / 删除 / 逐段重向量 / 库级重算。
 *
 * <h3>★ 为什么值得单独一组</h3>
 *
 * <p>这一族端点在很长时间里是「界面有按钮、后端没有端点」的重灾区：{@code PATCH /documents/{id}} 与 {@code PATCH
 * /documents/{id}/enabled} 返 404、{@code POST /segments/batch-move} 因为被 {@code GET /segments/{id}}
 * 的模式吃掉而返 <strong>500「服务内部错误」</strong>。 用户看到的是「重命名点了没反应 / 服务崩了」，而真相是「功能没做」或「方法写错了」——
 * 这类<strong>会撒谎</strong>的失败，闸门必须从外部接口口径上钉住。
 *
 * <h3>★ 断言挑的都是"会静默出错"的地方</h3>
 *
 * <ol>
 *   <li><strong>改名必须重写分段前缀</strong>（{@code SEG-I7}）：只改聚合根会留下「文档叫 A、分段检索文本里写着 B」——
 *       按新名字召回不到，而两处各自看起来都正常
 *   <li><strong>启停不许顺手重算</strong>：停用是"不参与检索"不是删除，重新启用后不该需要重新向量化
 *   <li>★ <strong>删除必须连带走掉分段与向量</strong>：只软删文档会把内容留在检索里（列表看不见、检索答得出来）
 *   <li><strong>库级重算的 queued 数要等于真正能重算的文档数</strong>：把分段失败的文档也算进去， 用户会对着一个永远不动的大数字等
 *   <li>★ <strong>方法不符必须 405</strong>：这是平台层修复的回归线（它曾经是 500）
 * </ol>
 *
 * <p>需要桩服务（{@code python backend/tools/openai_stub.py}），不在就跳过，不假绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentManagementTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  private static final String STUB_API_BASE =
      System.getenv().getOrDefault("AI_STUB_ROOT", "http://localhost:11434") + "/v1";

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  /** CREATOR 角色的固定 UUID（{@code V4__identity_seed_demo_accounts.sql}）—— 数据范围 OWN。 */
  private static final UUID CREATOR_ROLE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000004");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void requireStub() {
    assumeThat(reachable()).as("桩服务不在 " + STUB_API_BASE + " ⇒ 跳过").isTrue();
  }

  // ────────────────────────── 改名 ──────────────────────────

  /**
   * ★ 改名要<strong>重写分段的结构化前缀</strong>并把向量标成过期。
   *
   * <p>前缀里有文档名（{@code 12 §4.2}），而它已经写进了每段的 {@code search_text}/{@code content_hash}。
   * 只改文档行的话：按新名字召回不到，而文档页与分段页各自看起来都正常 —— 这正是 {@code SEG-I7} 要防的。
   */
  @Test
  void 改名会重写分段前缀并把向量标成过期() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminal(docId, 60)).as("失败原因：" + failReason(docId)).isEqualTo("SUCCEEDED");

    List<String> hashesBefore = contentHashes(docId);
    assertThat(hashesBefore).isNotEmpty();

    HttpResponse<String> res = patch("/api/v1/documents/" + docId, "{\"name\":\"改名后的合同\"}", admin);
    assertThat(res.statusCode()).as("改名：" + res.body()).isEqualTo(200);

    assertThat(nameOf(docId)).isEqualTo("改名后的合同");
    assertThat(searchTexts(docId))
        .as("每段的结构化前缀都要换成新文档名 —— 只改聚合根会让检索按新名字召回不到")
        .allSatisfy(text -> assertThat(text).startsWith("制度·采购部·改名后的合同｜"));
    assertThat(contentHashes(docId))
        .as("前缀组成变了 ⇒ contentHash 必须变（SEG-I7：它是「向量旧了」的判定依据）")
        .doesNotContainAnyElementsOf(hashesBefore);

    assertThat(status(docId)).as("向量旧了 ⇒ 文档回到待向量化").isEqualTo("PENDING_EMBEDDING");
    assertThat(segmentEmbeddingStatuses(docId))
        .as("分段也要一起退回，否则向量化器会跳过它们（只处理未完成的分段）")
        .containsOnly("PENDING_EMBEDDING");
  }

  /** 超长名称<strong>明确拒绝</strong>，且库里一个字都没变（不是静默截断）。 */
  @Test
  void 改名超长被拒且不静默截断() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    String before = nameOf(docId);

    String tooLong = "超".repeat(256);
    HttpResponse<String> res =
        patch("/api/v1/documents/" + docId, "{\"name\":\"" + tooLong + "\"}", admin);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).as("要给可读原因（含长度上限），不是「参数不合法」").contains("255");
    assertThat(nameOf(docId)).as("拒绝就必须原样不动，不许写进一个被截断的名字").isEqualTo(before);
  }

  // ────────────────────────── 启停 ──────────────────────────

  /** ★ 启停只动启停位：分段与向量都不许被动到（重新启用后不该需要重新向量化）。 */
  @Test
  void 启停只动启停位不碰分段与向量() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminal(docId, 60)).isEqualTo("SUCCEEDED");
    long vectors = vectorCount(docId);

    HttpResponse<String> off =
        patch("/api/v1/documents/" + docId + "/enabled", "{\"enabled\":false}", admin);
    assertThat(off.statusCode()).as("停用：" + off.body()).isEqualTo(200);
    assertThat(enabledOf(docId)).isFalse();
    assertThat(status(docId)).as("停用不是删除、也不是「向量旧了」").isEqualTo("SUCCEEDED");
    assertThat(segmentEmbeddingStatuses(docId)).containsOnly("EMBEDDED");
    assertThat(vectorCount(docId)).isEqualTo(vectors);

    HttpResponse<String> on =
        patch("/api/v1/documents/" + docId + "/enabled", "{\"enabled\":true}", admin);
    assertThat(on.statusCode()).isEqualTo(200);
    assertThat(enabledOf(docId)).isTrue();
  }

  /** 分段启停：同样只动那一位（停用 = 这一段不参与检索，不是删除）。 */
  @Test
  void 分段启停保留正文与向量() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminal(docId, 60)).isEqualTo("SUCCEEDED");

    String segmentId = segmentIds(docId).get(0);
    HttpResponse<String> off =
        patch("/api/v1/segments/" + segmentId + "/enabled", "{\"enabled\":false}", admin);
    assertThat(off.statusCode()).as("分段停用：" + off.body()).isEqualTo(200);
    assertThat(segmentEnabled(segmentId)).isFalse();
    assertThat(storedVectorSize(segmentId)).as("停用不删向量").isGreaterThan(0);
    assertThat(segmentEmbeddingStatuses(docId)).contains("EMBEDDED");
  }

  // ────────────────────────── 删除 ──────────────────────────

  /**
   * ★ 删除：文档软删（列表里消失），分段与向量<strong>真删</strong>。
   *
   * <p>只软删文档的话，它的内容仍然能被检索到 —— 列表看不见、检索答得出来，是最难排查的一种。
   */
  @Test
  void 删除连带走掉分段与向量() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminal(docId, 60)).isEqualTo("SUCCEEDED");
    assertThat(vectorCount(docId)).isGreaterThan(0);

    HttpResponse<String> res = delete("/api/v1/documents/" + docId, admin);
    assertThat(res.statusCode()).as("删除：" + res.body()).isEqualTo(200);

    assertThat(deletedAt(docId)).as("聚合根软删（保留行，02 §5.1）").isNotNull();
    assertThat(segmentCountRows(docId)).as("分段真删（没有软删列）").isZero();
    assertThat(vectorCount(docId)).as("★ 向量必须跟着删 —— 否则被删文档的内容仍能被检索到").isZero();

    HttpResponse<String> list = get("/api/v1/knowledge-bases/" + kbId + "/documents", admin);
    assertThat(list.body()).as("已删文档不该出现在列表里").doesNotContain(docId);
  }

  /** 不可见的文档：改名/删除都必须 <strong>404</strong>（403 等于确认"这个 id 存在"，可被枚举）。 */
  @Test
  void 不可见文档的管理动作返回404而不是403() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");

    String creator = createCreatorUser("doc_mgmt_own_user");

    HttpResponse<String> rename =
        patch("/api/v1/documents/" + docId, "{\"name\":\"越权改名\"}", creator);
    assertThat(rename.statusCode()).isEqualTo(404).as("越权改名：" + rename.body());

    HttpResponse<String> off =
        patch("/api/v1/documents/" + docId + "/enabled", "{\"enabled\":false}", creator);
    assertThat(off.statusCode()).isEqualTo(404);

    HttpResponse<String> del = delete("/api/v1/documents/" + docId, creator);
    assertThat(del.statusCode()).isEqualTo(404);
    assertThat(deletedAt(docId)).as("越权删除必须一行都没动").isNull();
  }

  // ────────────────────────── 重向量化（逐段 / 库级） ──────────────────────────

  /** ★ 逐段重向量复用文档级执行器 ⇒ 只重算那一段（其余段已是 EMBEDDED，会被跳过），向量不翻倍。 */
  @Test
  void 逐段重向量只重算那一段() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminal(docId, 60)).isEqualTo("SUCCEEDED");
    int segments = segmentCount(docId);
    long vectors = vectorCount(docId);

    String segmentId = segmentIds(docId).get(1);
    HttpResponse<String> res = post("/api/v1/segments/" + segmentId + "/embedding", null, admin);
    assertThat(res.statusCode()).as("逐段重向量：" + res.body()).isEqualTo(200);
    assertThat(res.body()).as("回执是该段的最新状态").contains(segmentId);

    assertThat(waitForTerminal(docId, 60)).as("失败原因：" + failReason(docId)).isEqualTo("SUCCEEDED");
    assertThat(processed(docId)).isEqualTo(segments);
    assertThat(vectorCount(docId)).as("幂等 upsert：向量条数不该翻倍").isEqualTo(vectors);
  }

  /**
   * ★ 库级重算的 {@code queued} 数 = <strong>真正有向量可重算</strong>的文档数。
   *
   * <p>分段失败的文档没有分段可算，算进去会让用户对着一个永远不动的大数字等（那数字还与进度对不上）。
   */
  @Test
  void 库级重算的排队数等于可重算的文档数() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String good = uploadDocx(admin, kbId, "structured.docx");
    String bad = uploadDocx(admin, kbId, "empty.docx");
    assertThat(waitForTerminal(bad, 30)).as("空文档 ⇒ 分段失败").isEqualTo("SEGMENT_FAILED");

    post("/api/v1/documents/" + good + "/embedding", null, admin);
    assertThat(waitForTerminal(good, 60)).isEqualTo("SUCCEEDED");

    HttpResponse<String> res = post("/api/v1/knowledge-bases/" + kbId + "/reembed", null, admin);
    assertThat(res.statusCode()).as("库级重算：" + res.body()).isEqualTo(200);
    JsonNode data = new ObjectMapper().readTree(res.body()).get("data");
    assertThat(data.get("queued").asInt()).as("只有 1 篇有向量可重算（分段失败那篇要被跳过）").isEqualTo(1);

    assertThat(waitForTerminal(good, 60)).as("失败原因：" + failReason(good)).isEqualTo("SUCCEEDED");
    assertThat(processed(good)).isEqualTo(segmentCount(good));
  }

  // ────────────────────────── 批量动作 ──────────────────────────

  /**
   * ★ 批量删除是<strong>尽力而为</strong>：一条 id 不存在不阻断其余，逐条给可读原因。
   *
   * <p>用户选了一屏、其中一条已被别人删掉就整批白做，是最让人恼火的一种「严格」。
   */
  @Test
  void 批量删除按条给回执且不阻断其余() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    int before = segmentCount(docId);
    assertThat(before).as("夹具前提：至少 3 段").isGreaterThanOrEqualTo(3);

    List<String> ids = segmentIds(docId);
    String ghost = "00000000-0000-0000-0000-000000000000";
    String body = "{\"ids\":[\"" + ids.get(0) + "\",\"" + ids.get(1) + "\",\"" + ghost + "\"]}";

    HttpResponse<String> res = deleteWithBody("/api/v1/segments/batch", body, admin);
    assertThat(res.statusCode()).as("批量删除：" + res.body()).isEqualTo(200);

    JsonNode data = new ObjectMapper().readTree(res.body()).get("data");
    assertThat(data.get("deleted").asInt()).as("两条真实存在的要删掉").isEqualTo(2);
    assertThat(data.get("failed")).as("不存在的那条要进 failed，而不是让整批失败").hasSize(1);
    assertThat(data.get("failed").get(0).get("reason").asText()).as("失败要给可读原因").contains("不存在");

    assertThat(segmentCountRows(docId)).as("库里少了两段").isEqualTo(before - 2);
    assertThat(segmentCount(docId)).as("文档计数跟着回填（不是留在旧数字上）").isEqualTo(before - 2);
    assertThat(ordinals(docId))
        .as("剩余分段序号要重编成 0..n-1 —— 否则界面显示「#0 #2 #3」而总数是 3")
        .containsExactlyElementsOf(
            java.util.stream.IntStream.range(0, before - 2).boxed().toList());
  }

  /** ★ 批量迁移 = 源删除 + 目标写入，三件事必须一起发生： ①②前缀按<strong>目标文档</strong>重算、序号追加到目标末尾；③两边文档的计数都要回填。 */
  @Test
  void 批量迁移把前缀与序号换到目标文档() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String source = uploadDocx(admin, kbId, "structured.docx");
    String target = uploadDocx(admin, kbId, "structured.docx");
    assertThat(waitForTerminal(target, 30)).isNotNull();
    // ★ 目标文档必须与源**名字不同**，否则两篇的检索前缀一模一样（前缀里含文档名），
    //   这条断言就证明不了任何东西 —— 第一版正是这么写的，被断言自己抓了出来
    assertThat(patch("/api/v1/documents/" + target, "{\"name\":\"目标文档甲\"}", admin).statusCode())
        .isEqualTo(200);

    String moved = segmentIds(source).get(0);
    int sourceBefore = segmentCount(source);
    int targetBefore = segmentCount(target);

    HttpResponse<String> res =
        post(
            "/api/v1/segments/batch-move",
            "{\"segmentIds\":[\"" + moved + "\"],\"targetDocumentId\":\"" + target + "\"}",
            admin);
    assertThat(res.statusCode()).as("批量迁移：" + res.body()).isEqualTo(200);
    JsonNode data = new ObjectMapper().readTree(res.body()).get("data");
    assertThat(data.get("moved").asInt()).isEqualTo(1);

    assertThat(documentIdOf(moved)).as("这一段现在属于目标文档").isEqualTo(target);
    assertThat(searchTextOf(moved))
        .as("前缀必须按**目标文档**重算 —— 否则检索文本里写的是原来那篇的名字")
        .startsWith("制度·采购部·目标文档甲｜");
    assertThat(embeddingStatusOf(moved)).as("换了文档与前缀 ⇒ 向量旧了，必须重新排队").isEqualTo("PENDING_EMBEDDING");
    assertThat(vectorRowsOf(moved)).as("旧向量的归属是错的 ⇒ 必须删掉，不能只标「待向量化」").isZero();

    assertThat(segmentCount(source)).as("源文档计数回填").isEqualTo(sourceBefore - 1);
    assertThat(segmentCount(target))
        .as("★ 目标文档计数也要回填（只回填一边，另一边会一直显示旧数字）")
        .isEqualTo(targetBefore + 1);
    assertThat(ordinals(source))
        .as("源文档剩余序号重编")
        .containsExactlyElementsOf(
            java.util.stream.IntStream.range(0, sourceBefore - 1).boxed().toList());
    assertThat(maxOrdinal(target))
        .as("★ 搬进来的段追加在目标文档**末尾**（不是插到中间去挤掉别人的序号）")
        .isEqualTo(targetBefore);
  }

  /** 把分段搬到它已经在的那篇文档 ⇒ 整批拒绝并说清（半途而废会把文档的分段劈到两个地方）。 */
  @Test
  void 批量迁移拒绝搬到同一篇文档() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    String segmentId = segmentIds(docId).get(0);

    HttpResponse<String> res =
        post(
            "/api/v1/segments/batch-move",
            "{\"segmentIds\":[\"" + segmentId + "\"],\"targetDocumentId\":\"" + docId + "\"}",
            admin);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).as("要说清「迁移是搬到别的文档」").contains("已经属于目标文档");
    assertThat(segmentCount(docId)).as("拒绝就必须一段都没动").isEqualTo(segmentIds(docId).size());
  }

  /**
   * 目标文档正在向量化中 ⇒ 迁移被拒（在途那一轮已经取好了分段清单，此刻的改动它看不到）。
   *
   * <p>★ 用 JDBC 直接把状态置成 {@code EMBEDDING} 是为了<strong>确定性地</strong>造出这个前置条件 —— 靠"快点点一下"去抢窗口，测试会时红时绿。
   */
  @Test
  void 目标文档向量化中时迁移被拒() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String source = uploadDocx(admin, kbId, "structured.docx");
    String target = uploadDocx(admin, kbId, "structured.docx");
    jdbc.update("update kb_document set status = 'EMBEDDING' where id = ?::uuid", target);

    HttpResponse<String> res =
        post(
            "/api/v1/segments/batch-move",
            "{\"segmentIds\":[\""
                + segmentIds(source).get(0)
                + "\"],\"targetDocumentId\":\""
                + target
                + "\"}",
            admin);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("向量化中");
    assertThat(segmentCountRows(source)).as("拒绝就必须一段都没搬走").isEqualTo(segmentCount(source));
  }

  // ────────────────────────── 平台层：不许把"方法写错"说成"服务器崩了" ──────────────────────────

  /**
   * ★ 路径认识、方法不对 ⇒ <strong>405 + 可用方法</strong>（曾经是 500「服务内部错误」）。
   *
   * <p>{@code /segments/batch} 只注册了 {@code DELETE}（批量删）。对它发 {@code POST} 时 Spring 会匹配到那条 映射、方法不符 ⇒
   * {@code HttpRequestMethodNotSupportedException}。 落进兜底 handler 时，用户看到的是 「服务内部错误，请把时间反馈给管理员」——
   * 一句把人引向后端堆栈、 而真实原因只是「方法写错了/接口还没做」的话。
   */
  @Test
  void 方法不符返回405而不是500() throws Exception {
    String admin = token("admin", "admin");
    HttpResponse<String> res = post("/api/v1/segments/batch", "{\"ids\":[]}", admin);

    assertThat(res.statusCode()).as("必须是 405，不是 500：" + res.body()).isEqualTo(405);
    assertThat(res.body()).contains("请求方法不被允许").contains("DELETE");
    assertThat(res.body()).doesNotContain("服务内部错误");
  }

  // ────────────────────────── 夹具与工具 ──────────────────────────

  private String token(String username, String password) throws Exception {
    String body = ("{\"username\":\"%s\",\"password\":\"%s\"}").formatted(username, password);
    HttpResponse<String> res = send("POST", "/api/v1/auth/login", body, null);
    Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
    assertThat(m.find()).as("登录失败：" + res.body()).isTrue();
    return m.group(1);
  }

  private String createKb(String token) throws Exception {
    String model =
        """
        {"name":"管理动作闸门向量-%s","kind":"EMBEDDING","supplier":"LOCAL_INFERENCE",
         "baseModel":"bge-m3","apiBase":"%s","dimension":768}\
        """
            .formatted(shortId(), STUB_API_BASE);
    HttpResponse<String> modelRes = send("POST", "/api/v1/models", model, token);
    Matcher m = ID.matcher(modelRes.body());
    assertThat(m.find()).as("建模型失败：" + modelRes.body()).isTrue();
    String modelId = m.group(1);

    String kb =
        """
        {"name":"管理动作闸门库-%s","category":"制度规范","importType":"LOCAL",
         "embeddingModelId":"%s","maxClassification":"SECRET"}\
        """
            .formatted(shortId(), modelId);
    HttpResponse<String> kbRes = send("POST", "/api/v1/knowledge-bases", kb, token);
    Matcher kbMatcher = ID.matcher(kbRes.body());
    assertThat(kbMatcher.find()).as("建库失败：" + kbRes.body()).isTrue();
    return kbMatcher.group(1);
  }

  /** 建一个 CREATOR（OWN 范围）用户并返回其令牌 —— 用来验"看不见的资源 ⇒ 404 而不是 403"。 */
  private String createCreatorUser(String username) throws Exception {
    String admin = token("admin", "admin");
    String body =
        """
        {"username":"%s","displayName":"%s","roleId":"%s","password":"Creator@123456"}\
        """
            .formatted(username, username, CREATOR_ROLE_ID);
    HttpResponse<String> res = send("POST", "/api/v1/users", body, admin);
    assertThat(res.statusCode()).as("建 CREATOR 用户失败：%s", res.body()).isEqualTo(200);
    return token(username, "Creator@123456");
  }

  /** 上传真实 DOCX（multipart）—— 建档即解析 + 入库即分块。 */
  private String uploadDocx(String token, String kbId, String fixture) throws Exception {
    byte[] bytes;
    try (InputStream in = getClass().getResourceAsStream("/fixtures/" + fixture)) {
      assertThat(in).as("夹具不在 classpath").isNotNull();
      bytes = in.readAllBytes();
    }
    String boundary = "----docmgmtboundary";
    var buf = new java.io.ByteArrayOutputStream();
    for (String[] field :
        new String[][] {{"docType", "制度"}, {"department", "采购部"}, {"classification", "INTERNAL"}}) {
      buf.write(
          ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n")
              .formatted(boundary, field[0], field[1])
              .getBytes(StandardCharsets.UTF_8));
    }
    buf.write(
        ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"%s\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .formatted(boundary, fixture)
            .getBytes(StandardCharsets.UTF_8));
    buf.write(bytes);
    buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
    buf.write(("--%s--\r\n").formatted(boundary).getBytes(StandardCharsets.UTF_8));

    HttpResponse<String> res =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/v1/knowledge-bases/"
                            + kbId
                            + "/documents"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buf.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Matcher m = ID.matcher(res.body());
    assertThat(m.find()).as("上传失败：" + res.body()).isTrue();
    String docId = m.group(1);
    // ★ 第 9 周起解析是**异步**的（扫描件的 OCR 实测每页约 3.6 秒，同步跑会把上传请求挂住十几秒
    //   —— 判据 ⑤ 要求「不阻塞其它文档」）⇒ "上传完就能读分段/状态"不再成立。
    //   测试要和界面一样**等**：轮询到解析落地（待向量化 / 分段失败）。
    waitParsed(docId, 30);
    return docId;
  }

  /** 等解析落地：{@code PENDING_EMBEDDING}（成功）或 {@code SEGMENT_FAILED}（失败）。 */
  private void waitParsed(String docId, int seconds) throws InterruptedException {
    for (int i = 0; i < seconds * 2; i++) {
      String current = status(docId);
      if ("PENDING_EMBEDDING".equals(current) || "SEGMENT_FAILED".equals(current)) {
        return;
      }
      Thread.sleep(500);
    }
  }

  private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
    return send("POST", path, body, bearer);
  }

  private HttpResponse<String> patch(String path, String body, String bearer) throws Exception {
    return send("PATCH", path, body, bearer);
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return send("GET", path, null, bearer);
  }

  private HttpResponse<String> delete(String path, String bearer) throws Exception {
    return send("DELETE", path, null, bearer);
  }

  /** DELETE 带请求体（契约 {@code batchDeleteSegments} 把 ids 放在 body 里）。 */
  private HttpResponse<String> deleteWithBody(String path, String body, String bearer)
      throws Exception {
    return send("DELETE", path, body, bearer);
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(30));
      if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.header("Content-Type", "application/json");
        builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      }
      if (bearer != null) {
        builder.header("Authorization", "Bearer " + bearer);
      }
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("请求失败：" + method + " " + path, e);
    }
  }

  // ────────────────────────── 直接读库（"状态是事实"的检验口） ──────────────────────────

  private String waitForTerminal(String docId, int seconds) throws InterruptedException {
    for (int i = 0; i < seconds * 2; i++) {
      String current = status(docId);
      if ("SUCCEEDED".equals(current) || "EMBEDDING_FAILED".equals(current)) {
        return current;
      }
      Thread.sleep(500);
    }
    return status(docId);
  }

  private String failReason(String docId) {
    return jdbc.queryForObject(
        "select coalesce(fail_reason, '(无)') from kb_document where id = ?::uuid",
        String.class,
        docId);
  }

  private String status(String docId) {
    return jdbc.queryForObject(
        "select status from kb_document where id = ?::uuid", String.class, docId);
  }

  private String nameOf(String docId) {
    return jdbc.queryForObject(
        "select name from kb_document where id = ?::uuid", String.class, docId);
  }

  private Boolean enabledOf(String docId) {
    return jdbc.queryForObject(
        "select enabled from kb_document where id = ?::uuid", Boolean.class, docId);
  }

  private Object deletedAt(String docId) {
    return jdbc.queryForObject(
        "select deleted_at from kb_document where id = ?::uuid", Object.class, docId);
  }

  private int processed(String docId) {
    return jdbc.queryForObject(
        "select processed_segments from kb_document where id = ?::uuid", Integer.class, docId);
  }

  private int segmentCount(String docId) {
    return jdbc.queryForObject(
        "select segment_count from kb_document where id = ?::uuid", Integer.class, docId);
  }

  private long vectorCount(String docId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from vec_embedding_768 where metadata::jsonb ->> 'document_id' = ?",
            Long.class,
            docId);
    return count == null ? 0 : count;
  }

  private int segmentCountRows(String docId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from kb_segment where document_id = ?::uuid", Integer.class, docId);
    return count == null ? 0 : count;
  }

  private List<String> segmentEmbeddingStatuses(String docId) {
    return jdbc.queryForList(
        "select embedding_status from kb_segment where document_id = ?::uuid", String.class, docId);
  }

  private List<String> segmentIds(String docId) {
    return jdbc.queryForList(
        "select id::text from kb_segment where document_id = ?::uuid order by ordinal",
        String.class,
        docId);
  }

  private List<String> searchTexts(String docId) {
    return jdbc.queryForList(
        "select search_text from kb_segment where document_id = ?::uuid order by ordinal",
        String.class,
        docId);
  }

  private List<String> contentHashes(String docId) {
    return jdbc.queryForList(
        "select content_hash from kb_segment where document_id = ?::uuid order by ordinal",
        String.class,
        docId);
  }

  private List<Integer> ordinals(String docId) {
    return jdbc.queryForList(
        "select ordinal from kb_segment where document_id = ?::uuid order by ordinal",
        Integer.class,
        docId);
  }

  private Integer maxOrdinal(String docId) {
    return jdbc.queryForObject(
        "select max(ordinal) from kb_segment where document_id = ?::uuid", Integer.class, docId);
  }

  private String documentIdOf(String segmentId) {
    return jdbc.queryForObject(
        "select document_id::text from kb_segment where id = ?::uuid", String.class, segmentId);
  }

  private String searchTextOf(String segmentId) {
    return jdbc.queryForObject(
        "select search_text from kb_segment where id = ?::uuid", String.class, segmentId);
  }

  private String embeddingStatusOf(String segmentId) {
    return jdbc.queryForObject(
        "select embedding_status from kb_segment where id = ?::uuid", String.class, segmentId);
  }

  /** 该分段在向量表里的行数（迁移后旧向量必须被删掉）。 */
  private int vectorRowsOf(String segmentId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from vec_embedding_768 where id = ?::uuid", Integer.class, segmentId);
    return count == null ? 0 : count;
  }

  private Boolean segmentEnabled(String segmentId) {
    return jdbc.queryForObject(
        "select enabled from kb_segment where id = ?::uuid", Boolean.class, segmentId);
  }

  private int storedVectorSize(String segmentId) {
    String literal =
        jdbc.queryForObject(
            "select embedding::text from vec_embedding_768 where id = ?::uuid",
            String.class,
            segmentId);
    assertThat(literal).as("该段应该有向量").isNotNull();
    return literal.split(",").length;
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static boolean reachable() {
    try {
      HttpResponse<String> res =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(2))
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(STUB_API_BASE + "/models"))
                      .timeout(Duration.ofSeconds(3))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      return res.statusCode() < 500;
    } catch (Exception e) {
      return false;
    }
  }
}
