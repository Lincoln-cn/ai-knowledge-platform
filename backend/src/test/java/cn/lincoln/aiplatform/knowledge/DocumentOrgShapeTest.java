package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.identity.application.OrgService;
import cn.lincoln.aiplatform.identity.domain.Org;
import cn.lincoln.aiplatform.support.PostgresImage;
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
 * 「文档的责任部门用稳定标识」的<strong>端到端哨兵</strong>（{@code T300} / issue #37，{@code 07 §11.4} 判据 1·2）。
 *
 * <h3>★ 为什么需要它（单元测试证明不了这件事）</h3>
 *
 * <p>{@code SearchTextTest} 只能钉住「{@code SearchText.prefix} 是纯函数、不翻译传入的部门标识」。
 * 而真正要防的窟窿在<strong>调用点</strong>：{@code SegmentService} / {@code DocumentService} 这两个拼前缀的地方 到底传的是
 * {@code code} 还是 {@code name}/{@code path} —— 那件事只有<strong>跑完整条建档→分块链路</strong>才看得见。
 * 所以这里的哨兵形态是：**真的建一个部门、真的建一份文档切出段、真的把那个部门改名/移动/改排序，然后逐字节比对**。
 *
 * <h3>★ 三条「不报错的错」（{@code 07 §11.2} 的三条实证）</h3>
 *
 * <ol>
 *   <li><b>改名</b>：名字是可变的纯展示信息 ⇒ 若它进了前缀与 {@code content_hash}，一次改名就让该部门下
 *       <strong>所有</strong>文档的向量被判定为"需要重算" —— 界面上什么都看不出来，只是白烧算力。
 *   <li><b>移动</b>：{@code path} 会变 ⇒ 整个子树下的文档一起连坐。
 *   <li><b>改排序</b>：同上，纯位置信息。
 * </ol>
 *
 * <p>三条的共同点：<strong>它们都不是内容改动</strong>，所以检索材料必须逐字节不变。反过来说，如果某天有人把 {@code OrgCodeResolver#codeOf} 换回
 * {@code name}，这条哨兵会立刻变红 —— 这正是它存在的理由（{@code SEG-I7} 的哈希是「向量旧了」的判定依据，误判一次就是一次全量重算）。
 *
 * <h3>★ 为什么每个用例自己建部门，而不是用种子里那些</h3>
 *
 * <p>种子部门（{@code V25}）是共享夹具：改它的名字会污染同一容器里别的用例的预期。 自己建一个（{@code code} 带随机后缀）既有真实的"改名/移动"可做，又不会影响别人。
 *
 * <p>★ 本类<strong>不依赖外部桩服务</strong>：建档→解析→分块全程在进程内完成，只有向量化才需要上游模型。 所以它不在本机跑不出结论的情况下跳过 ——
 * 一条会"静默跳过"的哨兵等于没有哨兵。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentOrgShapeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /**
   * 组织树根节点 id（{@code V25__identity_organization.sql} 的种子里是**字面量**，所以确定）。
   *
   * <p>★ 不用"按名字查"：名字可变可重名，拿它当定位手段本身就是这条哨兵要防的那个毛病。
   */
  private static final UUID ROOT_ORG_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  /** 搬运目标：种子里的一级部门（{@code V25} 的 {@code code} 是稳定标识，所以按 code 取）。 */
  private static final String MOVE_TARGET_CODE = "TECH";

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired OrgService orgs;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /**
   * ★<b>哨兵</b>：把一个部门<strong>改名 + 改排序 + 移动</strong>之后，它名下文档的每一段 {@code search_text} 与 {@code
   * content_hash} 必须<strong>逐字节不变</strong>（{@code 07 §11.4} 判据 1/2）。
   *
   * <h4>为什么断言的是「两份完整清单逐字节相等」而不是「以 code 开头」</h4>
   *
   * <p>「以 code 开头」是<strong>前缀</strong>的断言，而 {@code content_hash} 是<strong>整串</strong>的哈希 ——
   * 组织名一旦漏进哈希材料（哪怕只是其中一段），前缀看起来照样是对的（前缀的部门段确实是 code）， 而哈希已经变了。那种"改一半"的实现只有比对全量哈希才抓得住。
   *
   * <h4>为什么中间那三条"前提自检"不能省</h4>
   *
   * <p>「逐字节不变」有两个可能的成因：①前缀用的是稳定 code（我们要的）；②<strong>那三件改动压根没生效</strong>。 不先把 org 的 name/sort/path
   * 读出来确认真的变了，这条哨兵就会在"改名失败"时给出一个绿色的假象 —— 而"测试因错误原因通过"比红更贵。
   */
  @Test
  void renameMoveAndResortOfOrgDoNotChangeSegmentSearchTextOrHash() throws Exception {
    String admin = token("admin", "admin");
    UUID actor = UUID.randomUUID();

    // ① 建一个只属于本用例的部门（code 带随机后缀：全局唯一，且不会与种子树撞名）
    String code = "T300_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    Org org = orgs.create(ROOT_ORG_ID, code, "哨兵部门 " + code, 7, actor);

    // ② 建库 + 上传一份文档（责任部门 = 刚建的部门）⇒ 走真实的建档→解析→分块链路
    String kbId = createKb(admin);
    String docId = upload(admin, kbId, org.id());

    // ③ 记下每一段的检索文本与哈希
    List<String> searchTextsBefore = searchTexts(docId);
    List<String> hashesBefore = contentHashes(docId);
    assertThat(searchTextsBefore).as("夹具前提：必须真的切出了分段 —— 一段都没有的文档上跑这条哨兵等于没跑").isNotEmpty();
    assertThat(searchTextsBefore)
        .as("前缀里的部门那段必须是该部门的稳定 code，不是展示名、也不是 path（07 §11.2）")
        .allSatisfy(text -> assertThat(text).startsWith("制度·" + code + "·"));

    // ④ 三件「纯展示/位置」的改动：改名、改排序、移动
    String renamed = "改名后的哨兵部门 " + code;
    orgs.update(org.id(), renamed, null, null, actor);
    orgs.update(org.id(), null, 99, null, actor);
    orgs.move(org.id(), orgIdByCode(MOVE_TARGET_CODE), actor);

    // ★ 前提自检：三件改动必须真的落库了
    assertThat(nameOfOrg(org.id())).as("前提：改名必须生效").isEqualTo(renamed);
    assertThat(sortOfOrg(org.id())).as("前提：改排序必须生效").isEqualTo(99);
    assertThat(pathOfOrg(org.id()))
        .as("前提：移动必须生效（path 重写了，前缀材料里却**不许**出现它）")
        .isEqualTo("/ROOT/" + MOVE_TARGET_CODE + "/" + code + "/");

    // ⑤ 重读分段：逐字节不变
    assertThat(searchTexts(docId))
        .as(
            "改名/移动/改排序都是纯展示性改动 ⇒ search_text 必须逐字节不变。"
                + "它们一旦进了前缀，一次改名就会让该部门下所有文档的向量被白白判旧（07 §11.4 判据 1/2）")
        .containsExactlyElementsOf(searchTextsBefore);
    assertThat(contentHashes(docId))
        .as("content_hash 同理（SEG-I7）：它是「向量旧了」的判定依据，误判一次就是一次全量重算")
        .containsExactlyElementsOf(hashesBefore);

    // ⑥ ★ 真正的「有牙齿」的那一条：同一个部门、同一份内容、同一个文件名，只是**在改名之后**才入的库。
    //
    //   为什么必须有它：⑤ 单独看是**抓不到「部门名进材料」这个缺陷的** —— 改名不会回头重写已经落库的分段行，
    //   所以旧文档的 search_text/hash 无论如何都"没变"。它抓的是另一类回归（将来有人给改名接上"顺手重写前缀"）。
    //   而"改名之后新入的文档"与"改名之前旧入的文档"必须给出**同一份材料**，否则同一部门的两篇同内容文档
    //   会拿到两个哈希 ⇒ 同一份内容被重复向量化、前缀也自相矛盾 —— 那才是 issue #37 危害 1 的真实形状。
    String twinDocId = upload(admin, kbId, org.id());
    assertThat(searchTexts(twinDocId))
        .as(
            "同部门 + 同内容 + 同文件名 ⇒ 前缀必须与改名之前那篇**逐字节相同**："
                + "前缀里若放的是可变的展示名，改名后新入的文档就会拿到另一个前缀（issue #37 危害 1）")
        .containsExactlyElementsOf(searchTextsBefore);
    assertThat(contentHashes(twinDocId))
        .as("content_hash 同理：同一份内容在两个时刻入的库必须算出同一个哈希，否则向量缓存会一直重算")
        .containsExactlyElementsOf(hashesBefore);
  }

  // ────────────────────────── 夹具与工具 ──────────────────────────

  private String token(String username, String password) throws Exception {
    String body = ("{\"username\":\"%s\",\"password\":\"%s\"}").formatted(username, password);
    HttpResponse<String> res = send("POST", "/api/v1/auth/login", body, null);
    Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
    assertThat(m.find()).as("登录失败：%s", res.body()).isTrue();
    return m.group(1);
  }

  /**
   * 建一个库。
   *
   * <p>★ {@code embeddingModelId} 给一个随机 uuid、不建真模型：本用例只走到「分块完成、待向量化」， 绑不绑上游模型都不影响 ——
   * 而绑真模型就要起桩服务，那会让这条哨兵在没桩的机器上"静默跳过"。
   */
  private String createKb(String admin) throws Exception {
    String body =
        """
        {"name":"部门形态哨兵库-%s","category":"制度规范","description":"T300 哨兵用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"INTERNAL"}\
        """
            .formatted(shortId(), UUID.randomUUID());
    HttpResponse<String> res = send("POST", "/api/v1/knowledge-bases", body, admin);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    Matcher m = ID.matcher(res.body());
    assertThat(m.find()).as("建库响应里没有 id：%s", res.body()).isTrue();
    return m.group(1);
  }

  /**
   * multipart 上传一份文档（建档即解析 + 入库即分块），并等到解析落地。
   *
   * <p>★ 责任部门传的是 {@code orgId}（组织树里的部门 <strong>id</strong>）：{@code T300} / issue #37 起 自由文本 {@code
   * department} 已被摘掉 —— 它可变、可重名，做不了数据权限。
   */
  private String upload(String admin, String kbId, UUID orgId) throws Exception {
    String filename = "部门形态哨兵.md";
    String boundary = "----OrgShapeBoundary" + UUID.randomUUID();
    var buf = new java.io.ByteArrayOutputStream();
    for (String[] field :
        new String[][] {
          {"docType", "制度"}, {"orgId", orgId.toString()}, {"classification", "INTERNAL"}
        }) {
      buf.write(
          ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n")
              .formatted(boundary, field[0], field[1])
              .getBytes(StandardCharsets.UTF_8));
    }
    buf.write(
        ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"%s\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n")
            .formatted(boundary, filename)
            .getBytes(StandardCharsets.UTF_8));
    buf.write(sampleMarkdown().getBytes(StandardCharsets.UTF_8));
    buf.write(("\r\n--%s--\r\n").formatted(boundary).getBytes(StandardCharsets.UTF_8));

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
                .header("Authorization", "Bearer " + admin)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buf.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(res.statusCode()).as("上传文档失败：%s", res.body()).isEqualTo(200);
    Matcher m = ID.matcher(res.body());
    assertThat(m.find()).as("上传响应里没有 id：%s", res.body()).isTrue();
    String docId = m.group(1);

    waitParsed(docId, 30);
    assertThat(status(docId))
        .as("解析+分块必须落到「待向量化」（失败原因：%s）", failReason(docId))
        .isEqualTo("PENDING_EMBEDDING");
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

  /** 生成一份足够长的 Markdown（> 500 字）—— 保证切出多段，哨兵才有东西可盯。 */
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

  // ────────────────────────── 直接读库（"库里是什么"是唯一事实） ──────────────────────────

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

  private String status(String docId) {
    return jdbc.queryForObject(
        "select status from kb_document where id = ?::uuid", String.class, docId);
  }

  private String failReason(String docId) {
    return jdbc.queryForObject(
        "select coalesce(fail_reason, '(无)') from kb_document where id = ?::uuid",
        String.class,
        docId);
  }

  private String nameOfOrg(UUID orgId) {
    return jdbc.queryForObject("select name from iam_org where id = ?", String.class, orgId);
  }

  private Integer sortOfOrg(UUID orgId) {
    return jdbc.queryForObject("select sort from iam_org where id = ?", Integer.class, orgId);
  }

  private String pathOfOrg(UUID orgId) {
    return jdbc.queryForObject("select path from iam_org where id = ?", String.class, orgId);
  }

  /** 按稳定 {@code code} 取部门 id（★ 不按名字取：名字可变可重名，拿它定位就是要防的那个毛病）。 */
  private UUID orgIdByCode(String code) {
    return jdbc.queryForObject("select id from iam_org where code = ?", UUID.class, code);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
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
}
