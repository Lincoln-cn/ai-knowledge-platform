package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
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
import org.junit.jupiter.api.AfterEach;
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
 * Issue #56: documents whose responsible department could not be backfilled must be LISTABLE.
 *
 * <h3>The state this pins</h3>
 *
 * <p>V27 replaced kb_document.department (free text) with org_id (foreign key), backfilling
 * existing rows by name. Rows that did not match kept their original value in department_legacy (no
 * guessing, no silent loss), and the V27 comment said those rows "wait for an inspection round" -
 * while no such inspection existed. Measured on the dev database (2026-10-11): 112 documents, 3 of
 * them with org_id NULL and a non-empty department_legacy.
 *
 * <p>Harm shape (this project's number one disease): in the UI those rows merely show an EMPTY
 * department cell, which reads as "not filled in yet" rather than "the system needs you to fix
 * this". And department_legacy is an updatable=false archive column, so nobody would ever come back
 * to them. 07 11.4 item 3 already required "the unmatched ones must be listable" - until this
 * change that requirement lived only inside a migration comment.
 *
 * <h3>Criteria</h3>
 *
 * <ol>
 *   <li>{@code ?orgAssignment=UNASSIGNED} lists exactly the pending rows (and total matches the
 *       page);
 *   <li>after an assignment is given, the readback drops to 0;
 *   <li>the default (no parameter) is unchanged: all documents still come back.
 * </ol>
 *
 * <p>Canary: drop the orgId predicate from {@code DocumentRepositoryAdapter#filter} (and its bound
 * value) =&gt; criterion 1 goes red because the assigned document reappears in the pending list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentPendingOrgTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V25 seed: the ROOT org (kb_document.org_id is a foreign key - see Issue #48). */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  private final List<String> createdKbs = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String kbId : createdKbs) {
      jdbc.update("delete from kb_document where kb_id = ?::uuid", kbId);
      jdbc.update("delete from kb_knowledge_base where id = ?::uuid", kbId);
    }
    createdKbs.clear();
    jdbc.update("delete from ai_model where name like 'PENDING-ORG-%'");
  }

  @Test
  void pendingOrgDocumentsAreListableAndTheCountMatches() throws Exception {
    String token = login("admin", "admin");
    String kbId = createKb(token);

    // 1 assigned + 2 pending (org_id NULL, department_legacy non-empty) = the shape V27 left behind
    insertDocument(kbId, "zhengchang", ROOT_ORG, null);
    insertDocument(kbId, "daibu-A", null, "Old Dept A");
    insertDocument(kbId, "daibu-B", null, "Old Dept B");

    // ---- criterion 3 first: the default is unchanged (all three come back) ----
    String all = get("/api/v1/knowledge-bases/" + kbId + "/documents", token).body();
    assertThat(intField(all, "total"))
        .as(
            "default (no orgAssignment) must still list every document - the new filter changes"
                + " nothing")
        .isEqualTo(3);

    // ---- criterion 1: only the two pending rows, and total agrees with the page ----
    String pending =
        get("/api/v1/knowledge-bases/" + kbId + "/documents?orgAssignment=UNASSIGNED", token)
            .body();
    assertThat(intField(pending, "total"))
        .as("UNASSIGNED must count exactly the rows whose org_id is NULL (2 of 3)")
        .isEqualTo(2);
    assertThat(pending)
        .as("the assigned document must NOT appear in the pending list")
        .doesNotContain("zhengchang");
    assertThat(pending)
        .as("both pending documents must be listed - this is the whole point of #56")
        .contains("daibu-A")
        .contains("daibu-B");

    // ---- criterion 2: assigning the org makes it disappear from the pending list ----
    UUID first = documentIdByName(kbId, "daibu-A");
    jdbc.update("update kb_document set org_id = ?::uuid where id = ?", ROOT_ORG, first);
    String after =
        get("/api/v1/knowledge-bases/" + kbId + "/documents?orgAssignment=UNASSIGNED", token)
            .body();
    assertThat(intField(after, "total"))
        .as("after one assignment the pending readback must drop to 1")
        .isEqualTo(1);

    UUID second = documentIdByName(kbId, "daibu-B");
    jdbc.update("update kb_document set org_id = ?::uuid where id = ?", ROOT_ORG, second);
    String none =
        get("/api/v1/knowledge-bases/" + kbId + "/documents?orgAssignment=UNASSIGNED", token)
            .body();
    assertThat(intField(none, "total")).as("all assigned => pending readback is 0").isZero();
  }

  // ---------------------------- fixtures and readings ----------------------------

  private void insertDocument(String kbId, String name, String orgId, String legacy) {
    // ★ `#48`：裸 SQL 收进了 `DocumentFixture`（全仓唯一一处写 kb_document 的地方）。
    DocumentFixture.insert(
        jdbc,
        kbId,
        name,
        ClassificationLevel.INTERNAL.name(),
        DocumentFixture.Options.defaults().withOrgId(orgId).withDepartmentLegacy(legacy));
  }

  private UUID documentIdByName(String kbId, String name) {
    return jdbc.queryForObject(
        "select id from kb_document where kb_id = ?::uuid and name = ?", UUID.class, kbId, name);
  }

  private String createKb(String token) throws Exception {
    String model =
        """
        {"name":"PENDING-ORG-%s","kind":"EMBEDDING","supplier":"LOCAL_INFERENCE",
         "baseModel":"bge-m3","apiBase":"http://localhost:11434/v1","dimension":768}\
        """
            .formatted(UUID.randomUUID().toString().substring(0, 8));
    Matcher m = ID.matcher(post("/api/v1/models", model, token).body());
    assertThat(m.find()).as("creating the embedding model failed").isTrue();
    String kb =
        """
        {"name":"PENDING-ORG-KB-%s","category":"制度规范","importType":"LOCAL",
         "embeddingModelId":"%s","maxClassification":"CONFIDENTIAL"}\
        """
            .formatted(UUID.randomUUID().toString().substring(0, 8), m.group(1));
    Matcher kbMatcher = ID.matcher(post("/api/v1/knowledge-bases", kb, token).body());
    assertThat(kbMatcher.find()).as("creating the knowledge base failed").isTrue();
    createdKbs.add(kbMatcher.group(1));
    return kbMatcher.group(1);
  }

  private String login(String username, String password) throws Exception {
    HttpResponse<String> response =
        post(
            "/api/v1/auth/login",
            "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password),
            null);
    assertThat(response.statusCode()).as("login failed: %s", response.body()).isEqualTo(200);
    Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
    assertThat(m.find()).as("no accessToken: %s", response.body()).isTrue();
    return m.group(1);
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String body, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static int intField(String json, String field) {
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
    assertThat(m.find()).as("no %s field in: %s", field, json).isTrue();
    return Integer.parseInt(m.group(1));
  }
}
