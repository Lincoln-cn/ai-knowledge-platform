package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 会话附件上传（{@code POST /files}，{@code #187} 的最后一条入口，{@code 08 §4.9}）。
 *
 * <h3>为什么这四条</h3>
 *
 * <p>上传这条链路上"出错不报错"的地方有四处，每处一条：
 *
 * <ol>
 *   <li><strong>字节真的落了盘</strong>：只断言"返回 200"是不够的 —— 元数据写对了而文件没写， 用户点开附件才是空的，而那一步没有任何人会去测。
 *   <li><strong>空文件被拒</strong>：0 字节的附件在库里活得很好，点开是空白，用户会以为"平台把文件弄丢了"。
 *   <li><strong>超限被拒</strong>：{@code max-size-bytes} 是这一层自己的门（{@code servlet.multipart} 那道会先给
 *       413）；两条门里任意一条写错，另一条都能把它掩饰过去，所以要分别断言。
 *   <li><strong>文件名被清洗</strong>：{@code filename} 里可以带路径与换行 —— 前者是路径穿越的形状， 后者能把一行日志裂成两行。
 * </ol>
 *
 * <p>★ 与 records 那条端点同型的诚实边界：本类证明"文件存进去了"，<strong>不证明</strong>"附件能参与问答" （{@code ChatController} 收
 * {@code attachmentIds} 但不用，绑定与入库是下一批）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      // ★ 存储根指到 target 下：测试不许往工作目录里丢字节（清了又冒出来的脏文件会一路跟进提交）。
      "app.files.root=target/test-files"
    })
class FileUploadHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String BOUNDARY = "----aiplatform-file-upload-test";

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  /** 存储根目录：测试里读回来验证"字节真的在盘上"。 */
  @Value("${app.files.root}")
  String filesRoot;

  private final HttpClient http = HttpClient.newHttpClient();

  private final ObjectMapper mapper = new ObjectMapper();

  // ─────────────────────── 1 · 存得上、形状对、字节真在盘上 ───────────────────────

  /**
   * 正常路径：返回契约形状 {@code {id,name,size,mime}}、落一行 {@code cv_file}、**磁盘上有那份字节**。
   *
   * <p>★ 三条一起断言：只断前两条时，"落库成功、写盘失败"会静默通过（而那是用户最常遇到的那种故障）。
   */
  @Test
  void uploadingAFileStoresTheBytesAndReturnsTheContractShape() throws Exception {
    String token = login("admin", "admin");
    byte[] content = "这是一份测试附件的内容。".getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> response = upload(token, "季度报告.md", "text/markdown", content);

    assertThat(response.statusCode()).as("上传必须成功：%s", response.body()).isEqualTo(200);
    Map<String, Object> body = envelopeData(response.body());
    assertThat(body.get("name")).isEqualTo("季度报告.md");
    assertThat(((Number) body.get("size")).longValue()).isEqualTo(content.length);
    assertThat(body.get("mime")).isEqualTo("text/markdown");
    UUID fileId = UUID.fromString((String) body.get("id"));
    assertThat(body).as("不回存储键：那是服务端寻址用的，写进接口就是换存储时的破坏性变更").doesNotContainKey("storageKey");

    Map<String, Object> row =
        jdbc.queryForMap(
            "select name, size_bytes, mime, storage_key, owner_id from cv_file where id = ?::uuid",
            fileId.toString());
    assertThat(row.get("name")).isEqualTo("季度报告.md");
    assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(content.length);
    assertThat(row.get("mime")).isEqualTo("text/markdown");

    String storageKey = (String) row.get("storage_key");
    assertThat(storageKey).as("存储键从原始名里只取扩展名，不用原始名本身").endsWith(".md");
    Path stored = Path.of(filesRoot).resolve(storageKey);
    assertThat(Files.exists(stored)).as("字节必须真的落盘：%s", stored).isTrue();
    assertThat(Files.readAllBytes(stored)).as("盘上那份必须是原字节").isEqualTo(content);
  }

  // ─────────────────────── 2 · 空文件 ───────────────────────

  /**
   * 0 字节 ⇒ 400，且库里不留行。
   *
   * <p>★ 断言"不留行"很重要：先落记录再判空的那种写法，会留下一条指向空白附件的消息。
   */
  @Test
  void anEmptyFileIsRejected() throws Exception {
    String token = login("admin", "admin");
    long before = countFiles();

    HttpResponse<String> response = upload(token, "空文件.txt", "text/plain", new byte[0]);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).as("要说清「为什么」并且给出下一步").contains("文件内容为空");
    assertThat(countFiles()).as("被拒的请求不许留下元数据").isEqualTo(before);
  }

  // ─────────────────────── 3 · 超限 ───────────────────────

  /**
   * 超过 {@code app.files.max-size-bytes} ⇒ 400 且报出实际大小与上限。
   *
   * <p>★ 这里刻意用 25MB（上限 20MB）：留在 {@code servlet.multipart.max-file-size}（100MB）之下， 所以到得了我们这一层 ——
   * 若两个配置被写成一个值，这条用例证的就只是容器的行为。
   */
  @Test
  void aFileAboveTheLimitIsRejected() throws Exception {
    String token = login("admin", "admin");
    long before = countFiles();
    byte[] tooBig = new byte[25 * 1024 * 1024];

    HttpResponse<String> response = upload(token, "大文件.bin", "application/octet-stream", tooBig);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("文件超过上限").contains("26214400").contains("20971520");
    assertThat(countFiles()).isEqualTo(before);
  }

  // ─────────────────────── 4 · 文件名清洗 ───────────────────────

  /**
   * 带路径与换行的文件名：展示名只留最后一段、去控制字符；存储键仍是自己生成的 uuid。
   *
   * <p>★ 关键是第二半：如果哪个实现图省事拿原始名当存储键，{@code ../../} 就变成了相对根目录的穿越。
   */
  @Test
  void aNameWithPathAndControlCharactersIsCleanedUp() throws Exception {
    String token = login("admin", "admin");
    byte[] content = "x".getBytes(StandardCharsets.UTF_8);

    HttpResponse<String> response =
        upload(token, "../..\\dir\\报告\n表.pdf", "application/pdf", content);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    Map<String, Object> body = envelopeData(response.body());
    assertThat(body.get("name")).as("只留最后一段 + 去掉换行").isEqualTo("报告表.pdf");

    String storageKey =
        jdbc.queryForObject(
            "select storage_key from cv_file where id = ?::uuid", String.class, body.get("id"));
    assertThat(storageKey).as("存储键必须是自生成的 uuid，不是用户给的名字").matches("^[0-9a-f]{32}\\.pdf$");
  }

  // ────────────────────────────── 工具 ──────────────────────────────

  /**
   * 拼一个 multipart 请求并发出去。
   *
   * <p>★ 手工拼 boundary 而不是引 {@code MockMvc}：这条端点跑在真实端口上（{@code RANDOM_PORT}）， 用 {@code MockMvc} 就绕开了
   * multipart 的容器解析 —— 而 {@code servlet.multipart.max-file-size} 那一层的 413 正是在容器里发生的。
   */
  private HttpResponse<String> upload(
      String token, String fileName, String contentType, byte[] content) throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + BOUNDARY
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName
                + "\"\r\n"
                + "Content-Type: "
                + contentType
                + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(content);
    body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/files"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private Map<String, Object> envelopeData(String body) {
    Map<String, Object> envelope =
        mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) envelope.get("data");
    assertThat(data).as("data 段必须是对象：%s", body).isNotNull();
    return data;
  }

  private long countFiles() {
    Long count = jdbc.queryForObject("select count(*) from cv_file", Long.class);
    return count == null ? 0L : count;
  }

  private String login(String username, String password) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                    StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(response.statusCode()).as("%s 必须能登录：%s", username, response.body()).isEqualTo(200);
    Map<String, Object> envelope =
        mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) envelope.get("data");
    return (String) data.get("accessToken");
  }

  private String base() {
    return "http://localhost:" + port;
  }
}
