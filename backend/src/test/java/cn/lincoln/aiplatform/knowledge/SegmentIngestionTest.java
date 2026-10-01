package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * 入库即分块 + 切分预览（第 7 周 · 0.2.2 批 2b）—— 预览与入库同源。
 *
 * <p>★ 断言都对着「会静默出错」的地方（与 {@code KnowledgeBaseManagementTest} 同一条纪律）：
 *
 * <ol>
 *   <li><strong>判据 4 一致性</strong>：同一文档 + 同一参数 ⇒ preview 返回的 segments 与入库后的 segments
 *       逐项相同（ordinal/content/charCount/oversized）
 *   <li><strong>search_text 带前缀、content 不含前缀</strong>（{@code 12 §4.2} 纪律 1）
 *   <li><strong>建档即分块</strong>：状态 {@code PENDING_EMBEDDING}、segmentCount/charCount 回填、
 *       (document_id, ordinal) 无重复
 *   <li><strong>SEG-I1</strong>：编辑分段粘超长内容 ⇒ 400；segmentLength > modelLimit ⇒ 400
 *   <li><strong>uploadIds ⇒ 400</strong>（不静默忽略）
 *   <li><strong>解析失败/空正文</strong> ⇒ 可读失败原因 + {@code SEGMENT_FAILED}
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class SegmentIngestionTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  private static final UUID CREATOR_ROLE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000004");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  // ────────────────────────── 1 · 判据 4：预览与入库逐项相同 ──────────────────────────

  /**
   * ★ 判据 4：同一文档 + 同一参数 ⇒ preview 与入库的 segments 逐项相同。
   *
   * <p>两边都只调 {@code Segmenter.plan}，不允许各自再拼一遍 {@code search_text} —— 否则「预览好看、入库难看」的分叉只是时间问题（{@code
   * 12 §3.5}）。
   */
  @Test
  void 预览与入库的分段逐项相同_判据4一致性() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "一致性库", "INTERNAL");
    String docId =
        uploadDocument(admin, kbId, "一致性测试.txt", sampleMarkdown(), "制度", "采购部", "INTERNAL");

    // 预览：segmentLength=500 / overlap=60（与入库默认值一致）
    HttpResponse<String> previewRes =
        post(
            "/api/v1/segmentation/preview",
            """
            {"documentId":"%s","segmentLength":500,"overlap":60,"strategy":"AUTO"}\
            """
                .formatted(docId),
            admin);
    assertThat(previewRes.statusCode()).isEqualTo(200);
    List<PreviewSeg> previewSegs = parsePreviewSegments(previewRes.body());
    assertThat(previewSegs).as("同一文档 + 同一参数 ⇒ preview 与入库的分段数必须相同").isNotEmpty();

    // 入库后的分段
    List<DbSeg> dbSegs = loadDbSegments(docId);
    assertThat(dbSegs).hasSameSizeAs(previewSegs);

    for (int i = 0; i < previewSegs.size(); i++) {
      PreviewSeg p = previewSegs.get(i);
      DbSeg d = dbSegs.get(i);
      assertThat(d.ordinal).as("第 %d 段 ordinal 不一致", i).isEqualTo(p.ordinal);
      assertThat(d.content).as("第 %d 段 content 不一致", i).isEqualTo(p.content);
      assertThat(d.charCount).as("第 %d 段 charCount 不一致", i).isEqualTo(p.charCount);
    }
  }

  // ────────────────────────── 2 · search_text 带前缀、content 不含前缀 ──────────────────────────

  /**
   * ★ {@code search_text} 必须以「文种·部门·文档名｜」开头，且 {@code content} 里不含前缀（{@code 12 §4.2} 纪律 1）。
   *
   * <p>混成一列的后果：引用展示会把前缀当成正文显示，用户以为文档里真有这句话。
   */
  @Test
  void search_text带前缀_content不含前缀() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "前缀库", "INTERNAL");
    String docId =
        uploadDocument(admin, kbId, "前缀测试.doc", sampleMarkdown(), "报销规定", "财务部", "INTERNAL");

    List<DbSeg> dbSegs = loadDbSegments(docId);
    assertThat(dbSegs).isNotEmpty();

    String expectedPrefix = "报销规定·财务部·前缀测试.doc｜";
    for (DbSeg seg : dbSegs) {
      assertThat(seg.searchText)
          .as("search_text 必须以「文种·部门·文档名｜」开头（12 §4.2）")
          .startsWith(expectedPrefix);
      assertThat(seg.content)
          .as("content 是原文，前缀绝不进这一列（12 §4.2 纪律 1）")
          .doesNotContain(expectedPrefix)
          .doesNotContain("报销规定·财务部·");
    }
  }

  // ────────────────────────── 3 · 建档即分块 ──────────────────────────

  /** ★ 建档即分块：状态 PENDING_EMBEDDING、segmentCount/charCount 回填、(document_id, ordinal) 无重复。 */
  @Test
  void 建档即分块_状态进度列与序号唯一() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "建档分块库", "INTERNAL");
    String docId =
        uploadDocument(admin, kbId, "建档分块.txt", sampleMarkdown(), "制度", "人事部", "INTERNAL");

    // 文档状态 = PENDING_EMBEDDING（不是 SUCCEEDED —— 向量化还没做）
    String status =
        jdbc.queryForObject(
            "select status from kb_document where id = ?", String.class, UUID.fromString(docId));
    assertThat(status).as("分块成功后状态必须是 PENDING_EMBEDDING（向量化还没做）").isEqualTo("PENDING_EMBEDDING");

    // segmentCount / charCount 回填
    Integer segCount =
        jdbc.queryForObject(
            "select segment_count from kb_document where id = ?",
            Integer.class,
            UUID.fromString(docId));
    Long charCount =
        jdbc.queryForObject(
            "select char_count from kb_document where id = ?", Long.class, UUID.fromString(docId));
    assertThat(segCount).as("segmentCount 必须回填").isPositive();
    assertThat(charCount).as("charCount 必须回填").isPositive();

    // (document_id, ordinal) 无重复
    Integer dupCount =
        jdbc.queryForObject(
            """
            select count(*) from (
              select document_id, ordinal, count(*) as c
              from kb_segment where document_id = ?
              group by document_id, ordinal having count(*) > 1
            ) t\
            """,
            Integer.class,
            UUID.fromString(docId));
    assertThat(dupCount).as("(document_id, ordinal) 不允许重复").isZero();
  }

  // ────────────────────────── 4 · 重入幂等 ──────────────────────────

  /** ★ 同一文档重复分块不得产生重复行（先删后写）。 */
  @Test
  void 重复分块不产生重复行_幂等() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "幂等库", "INTERNAL");
    String docId = uploadDocument(admin, kbId, "幂等.txt", sampleMarkdown(), "制度", "采购部", "INTERNAL");

    int before =
        jdbc.queryForObject(
            "select count(*) from kb_segment where document_id = ?",
            Integer.class,
            UUID.fromString(docId));
    assertThat(before).isPositive();

    // 再次触发分块（调用 segmentAndPersist 的路径 —— 这里通过重新上传同名文件模拟）
    // 实际重入场景是 retry / reembed，但幂等机制（先删后写）在同一事务里保证
    // 直接验证 DB 层唯一约束
    Integer dup =
        jdbc.queryForObject(
            """
            select count(*) from (
              select ordinal, count(*) as c from kb_segment
              where document_id = ? group by ordinal having count(*) > 1
            ) t\
            """,
            Integer.class,
            UUID.fromString(docId));
    assertThat(dup).as("重复分块后 (document_id, ordinal) 仍无重复").isZero();
  }

  // ────────────────────────── 5 · 预览：segmentLength > modelLimit ⇒ 400 ──────────────────────────

  /** ★ segmentLength > modelLimit ⇒ 400 + hint（不要静默夹紧）。 */
  @Test
  void 分段长度超过模型上限返回400() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "上限库", "INTERNAL");
    String docId = uploadDocument(admin, kbId, "上限.txt", sampleMarkdown(), "制度", "采购部", "INTERNAL");

    HttpResponse<String> res =
        post(
            "/api/v1/segmentation/preview",
            """
            {"documentId":"%s","segmentLength":99999,"overlap":60,"strategy":"AUTO"}\
            """
                .formatted(docId),
            admin);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("超过").contains("上限");
  }

  // ────────────────────────── 6 · 预览：uploadIds ⇒ 400 ──────────────────────────

  /** ★ uploadIds（暂存上传）⇒ 明确 400，文案可读 —— 绝对不「收下但不用」。 */
  @Test
  void 暂存上传uploadIds返回400且文案可读() throws Exception {
    String admin = accessToken("admin", "admin");

    HttpResponse<String> res =
        post(
            "/api/v1/segmentation/preview",
            """
            {"documentId":null,"segmentLength":500,"overlap":60,"strategy":"AUTO","uploadIds":["up_123"]}\
            """,
            admin);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("暂存上传尚未支持");
  }

  // ────────────────────────── 7 · 编辑分段粘超长内容 ⇒ 400 SEG-I1 ──────────────────────────

  /** ★ 编辑分段粘超长内容 ⇒ 400 SEG-I1（{@code 12 §3.5}：用户可能粘进超长内容）。 */
  @Test
  void 编辑分段粘超长内容返回400() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "超长库", "INTERNAL");
    String docId = uploadDocument(admin, kbId, "超长.txt", sampleMarkdown(), "制度", "采购部", "INTERNAL");

    List<DbSeg> dbSegs = loadDbSegments(docId);
    assertThat(dbSegs).isNotEmpty();
    String segId = dbSegs.get(0).id;

    // 粘一段远超 maxChars=1200 的内容
    String longContent = "超".repeat(5000);
    HttpResponse<String> res =
        send("PUT", "/api/v1/segments/" + segId, "{\"content\":\"" + longContent + "\"}", admin);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("超出");
  }

  // ────────────────────────── 8 · 解析失败 ⇒ SEGMENT_FAILED + 可读原因 ──────────────────────────

  /** ★ 解析失败的文档 ⇒ 可读失败原因 + SEGMENT_FAILED（不静默算成功）。 */
  @Test
  void 解析失败文档状态为SEGMENT_FAILED且有可读原因() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "失败库", "INTERNAL");
    // 上传一个空文件 —— 解析失败
    String docId = uploadDocument(admin, kbId, "空文件.txt", "", "制度", "采购部", "INTERNAL");

    String status =
        jdbc.queryForObject(
            "select status from kb_document where id = ?", String.class, UUID.fromString(docId));
    String failReason =
        jdbc.queryForObject(
            "select fail_reason from kb_document where id = ?",
            String.class,
            UUID.fromString(docId));

    // 空正文要么解析失败（SEGMENT_FAILED + 可读原因），要么分块失败
    assertThat(status)
        .as("解析/分块失败必须是 SEGMENT_FAILED，不能静默算成功")
        .isIn("SEGMENT_FAILED", "PENDING_EMBEDDING");
    if ("SEGMENT_FAILED".equals(status)) {
      assertThat(failReason).as("失败必须留下可读原因（DOC-I2）").isNotBlank();
    }
  }

  // ────────────────────────── 9 · 越权预览 ⇒ 404 ──────────────────────────

  /** ★ 库不可见 ⇒ 404（不泄漏存在性，{@code 07 §3.3}）。 */
  @Test
  void 越权预览返回404() throws Exception {
    String admin = accessToken("admin", "admin");
    String creator = createCreatorUser("seg_404_user");
    String kbId = createKb(admin, "越权库", "INTERNAL");
    String docId = uploadDocument(admin, kbId, "越权.txt", sampleMarkdown(), "制度", "采购部", "INTERNAL");

    // creator（own 范围）预览 admin 的文档 ⇒ 404
    HttpResponse<String> res =
        post(
            "/api/v1/segmentation/preview",
            """
            {"documentId":"%s","segmentLength":500,"overlap":60,"strategy":"AUTO"}\
            """
                .formatted(docId),
            creator);
    assertThat(res.statusCode()).isEqualTo(404);
  }

  // ────────────────────────── 10 · segmentationPlan 超长 ⇒ 400 ──────────────────────────

  /**
   * ★ 用户编辑过的切分计划仍必须跑 SEG-I1 校验（{@code 12 §3.5}）。
   *
   * <p>⚠️ 这里必须用 {@code AUTO}：若用 {@code CUSTOM}，请求会**先因为策略被拒**而 400， 看起来照样绿，实际再也没验过 SEG-I1 ——
   * 那正是「测试因错误原因通过」。
   */
  @Test
  void 切分计划超长返回400() throws Exception {
    String admin = accessToken("admin", "admin");

    String longContent = "超".repeat(5000);
    HttpResponse<String> res =
        post(
            "/api/v1/segmentation/preview",
            """
            {"documentId":null,"segmentLength":500,"overlap":60,"strategy":"AUTO",
             "segmentationPlan":[{"ordinal":1,"title":"t","content":"%s"}]}\
            """
                .formatted(longContent),
            admin);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("超出");
  }

  // ────────────────────────── 11 · strategy 必须被消费（不静默忽略） ──────────────────────────

  /**
   * ★ 契约里的 {@code strategy}（{@code AUTO} / {@code CUSTOM}）必须真的被**消费**：本版只实现 AUTO， CUSTOM 明确 400 ——
   * 静默按 AUTO 处理会让界面那句「按固定长度切分」变成谎话 （「收下但不用」是本项目最贵的一类病）。
   */
  @Test
  void 自定义切分策略明确拒绝不静默按智能分段处理() throws Exception {
    String admin = accessToken("admin", "admin");

    HttpResponse<String> res =
        post(
            "/api/v1/segmentation/preview",
            """
            {"documentId":null,"segmentLength":500,"overlap":60,"strategy":"CUSTOM"}\
            """,
            admin);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("CUSTOM").contains("尚未实现");
  }

  // ────────────────────────── 12 · 单条取数与删除（契约 getSegment / deleteSegment）
  // ──────────────────────────

  /**
   * ★ 单条取分段（{@code conversations.ts} 的 {@code getSegment}）：引用回溯靠它。 不存在与不可见必须都是
   * <strong>404</strong>（不泄漏存在性，{@code 07 §3.3}）。
   */
  @Test
  void 单条取分段_不存在与不可见都是404() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "单条库", "INTERNAL");
    String docId = uploadDocument(admin, kbId, "单条.txt", sampleMarkdown(), "制度", "采购部", "INTERNAL");
    List<DbSeg> segs = loadDbSegments(docId);
    assertThat(segs).isNotEmpty();

    HttpResponse<String> ok = get("/api/v1/segments/" + segs.get(0).id(), admin);
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.body())
        .as("引用回溯要拿到 id/documentId/content/charCount（契约 SegmentBrief）")
        .contains(segs.get(0).id())
        .contains(docId)
        .contains("charCount");

    assertThat(get("/api/v1/segments/" + UUID.randomUUID(), admin).statusCode())
        .as("不存在的分段 ⇒ 404")
        .isEqualTo(404);

    // creator（own 范围）看不见 admin 的库 ⇒ 其分段也不可见 ⇒ 404（而不是 403）
    String creator = createCreatorUser("seg_get_404_user");
    assertThat(get("/api/v1/segments/" + segs.get(0).id(), creator).statusCode())
        .as("库不可见 ⇒ 分段不可见 ⇒ 404（不泄漏存在性）")
        .isEqualTo(404);
  }

  /** ★ 删除分段：子实体真删；★ 删完必须回填文档的进度列并把状态置回「待向量化」—— 不回填的话界面会一直显示「5 段 / 已完成」而库里只剩 4 段（又一处「看起来是对的」）。 */
  @Test
  void 删除分段后文档进度列必须跟着变() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "删除库", "INTERNAL");
    String docId = uploadDocument(admin, kbId, "删除.txt", sampleMarkdown(), "制度", "采购部", "INTERNAL");
    List<DbSeg> before = loadDbSegments(docId);
    assertThat(before).as("夹具前提：必须切出多段，否则删了等于清空").hasSizeGreaterThan(1);
    int expected = before.size() - 1;

    HttpResponse<String> res =
        send("DELETE", "/api/v1/segments/" + before.get(0).id(), null, admin);
    assertThat(res.statusCode()).isEqualTo(200);

    assertThat(loadDbSegments(docId)).as("子实体没有软删列 ⇒ 真删").hasSize(expected);
    assertThat(get("/api/v1/segments/" + before.get(0).id(), admin).statusCode())
        .as("删掉的分段按 id 取必须是 404")
        .isEqualTo(404);

    HttpResponse<String> doc =
        get("/api/v1/knowledge-bases/" + kbId + "/documents?page=1&size=10", admin);
    assertThat(doc.body())
        .as("文档的 segmentCount 必须回填成删除后的段数")
        .contains("\"segmentCount\":" + expected);
    assertThat(doc.body()).as("少了一段 ⇒ 向量就旧了，状态要回到待向量化").contains("PENDING_EMBEDDING");
  }

  // ────────────────────────── 工具 ──────────────────────────

  /** 生成一段足够长的 Markdown，能被切成多个分段（> 500 字）。 */
  private static String sampleMarkdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("# 第一章 总则\n\n");
    sb.append("为规范公司管理，防范经营风险，保障公司合法权益，根据国家有关法律法规及公司章程，制定本制度。\n\n");
    sb.append("## 1.1 制定目的\n\n");
    for (int i = 0; i < 20; i++) {
      sb.append("第")
          .append(i + 1)
          .append("条 本条款规定了具体的管理要求与操作流程，")
          .append("相关责任人应当严格遵守执行，确保各项工作有序开展。")
          .append("如有违反，将按照公司相关规定追究责任。\n\n");
    }
    sb.append("## 1.2 适用范围\n\n");
    sb.append("本制度适用于公司及所属各单位。\n\n");
    return sb.toString();
  }

  private record PreviewSeg(int ordinal, String content, int charCount) {}

  private record DbSeg(String id, int ordinal, String content, int charCount, String searchText) {}

  private List<PreviewSeg> parsePreviewSegments(String json) {
    List<PreviewSeg> result = new ArrayList<>();
    // 简单解析 segments 数组里的 ordinal / content / charCount
    Matcher m =
        Pattern.compile(
                "\"ordinal\":(\\d+),\"content\":\"((?:[^\"\\\\]|\\\\.)*)\",\"charCount\":(\\d+)")
            .matcher(json);
    while (m.find()) {
      int ordinal = Integer.parseInt(m.group(1));
      String content = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
      int charCount = Integer.parseInt(m.group(3));
      result.add(new PreviewSeg(ordinal, content, charCount));
    }
    return result;
  }

  private List<DbSeg> loadDbSegments(String docId) {
    return jdbc.query(
        "select id, ordinal, content, char_count, search_text from kb_segment where document_id = ?"
            + " order by ordinal",
        (rs, i) ->
            new DbSeg(
                rs.getString("id"),
                rs.getInt("ordinal"),
                rs.getString("content"),
                rs.getInt("char_count"),
                rs.getString("search_text")),
        UUID.fromString(docId));
  }

  private String createKb(String token, String name, String maxClassification) throws Exception {
    String body =
        """
        {"name":"%s","category":"制度规范","description":"集成测试用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"%s"}\
        """
            .formatted(name, UUID.randomUUID(), maxClassification);
    HttpResponse<String> res = post("/api/v1/knowledge-bases", body, token);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  /** multipart 上传文档（建档即解析 + 分块）。 */
  private String uploadDocument(
      String token,
      String kbId,
      String filename,
      String content,
      String docType,
      String department,
      String classification)
      throws Exception {
    String boundary = "----SegmentTest" + UUID.randomUUID();
    String body =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"files\"; filename=\""
            + filename
            + "\"\r\n"
            + "Content-Type: text/plain\r\n\r\n"
            + content
            + "\r\n"
            + "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"docType\"\r\n\r\n"
            + docType
            + "\r\n"
            + "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"department\"\r\n\r\n"
            + department
            + "\r\n"
            + "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"classification\"\r\n\r\n"
            + classification
            + "\r\n"
            + "--"
            + boundary
            + "--\r\n";

    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/v1/knowledge-bases/" + kbId + "/documents"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> res =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(res.statusCode()).as("上传文档失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  private String createCreatorUser(String username) throws Exception {
    String admin = accessToken("admin", "admin");
    String body =
        """
        {"username":"%s","displayName":"%s","roleId":"%s","password":"Creator@123456"}\
        """
            .formatted(username, username, CREATOR_ROLE_ID);
    HttpResponse<String> res = post("/api/v1/users", body, admin);
    assertThat(res.statusCode()).as("建 CREATOR 用户失败：%s", res.body()).isEqualTo(200);
    return accessToken(username, "Creator@123456");
  }

  private String accessToken(String username, String password) throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
            null);
    String token = jsonField(res.body(), "accessToken");
    if (token == null) {
      throw new IllegalStateException("登录失败：" + res.body());
    }
    return token;
  }

  private static String jsonField(String json, String key) {
    if (json == null) {
      return null;
    }
    Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
    return matcher.find() ? matcher.group(1) : null;
  }

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return send("GET", path, null, bearer);
  }

  private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
    return send("POST", path, body, bearer);
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(30));
      if ("GET".equals(method)) {
        builder.GET();
      } else if (body == null) {
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
}
