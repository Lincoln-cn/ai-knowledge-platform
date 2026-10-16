package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
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
 * Issue #150: documents must be assignable to a department AFTER they exist.
 *
 * <h3>Why this test exists</h3>
 *
 * <p>Issue #56 made the unbackfilled documents LISTABLE (?orgAssignment=UNASSIGNED), but orgId
 * could only ever be set in the two import dialogs - DocumentController had no PATCH/PUT at all. So
 * the pending list could be found but never resolved (the only workaround was delete + recreate,
 * which throws away the archived department_legacy value). This test pins the missing write path.
 *
 * <h3>Criteria (matching #150)</h3>
 *
 * <ol>
 *   <li>after assigning, the document leaves the UNASSIGNED list (total 1 -&gt; 0);
 *   <li>a department that does not exist is REJECTED (400), not silently written;
 *   <li>canary: removing the orgDirectory check makes criterion 2 red;
 *   <li>department_legacy (the archived original value) is NOT touched by the assignment.
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentAssignOrgTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V25 seed: the ROOT org. */
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
    jdbc.update("delete from ai_model where name like 'ASSIGN-ORG-%'");
  }

  @Test
  void assigningADepartmentResolvesThePendingEntryAndKeepsTheArchive() throws Exception {
    String token = login("admin", "admin");
    String kbId = createKb(token);
    String documentId = insertPendingDocument(kbId, "daibu", "Old Dept A");

    // Precondition (from #56's own reading): it starts out in the pending list.
    assertThat(pendingTotal(kbId, token))
        .as("precondition: the document starts pending")
        .isEqualTo(1);

    // ---- criterion 1: assigning resolves it ----
    HttpResponse<String> response =
        patch(
            "/api/v1/knowledge-bases/" + kbId + "/documents/" + documentId + "/org",
            "{\"orgId\":\"" + ROOT_ORG + "\"}",
            token);
    assertThat(response.statusCode())
        .as("assigning a valid department: %s", response.body())
        .isEqualTo(200);
    assertThat(pendingTotal(kbId, token))
        .as("★ criterion 1: after assigning, the document is no longer pending")
        .isZero();
    assertThat(orgIdInDb(UUID.fromString(documentId)))
        .as("the write must actually land in the database")
        .isEqualTo(ROOT_ORG);

    // ---- criterion 4: the archived original value survives ----
    assertThat(legacyInDb(UUID.fromString(documentId)))
        .as("★ criterion 4: department_legacy is history - assigning must not clear or rewrite it")
        .isEqualTo("Old Dept A");
  }

  @Test
  void assigningANonexistentDepartmentIsRejected() throws Exception {
    String token = login("admin", "admin");
    String kbId = createKb(token);
    String documentId = insertPendingDocument(kbId, "daibu-2", "Old Dept B");

    HttpResponse<String> response =
        patch(
            "/api/v1/knowledge-bases/" + kbId + "/documents/" + documentId + "/org",
            "{\"orgId\":\"" + UUID.randomUUID() + "\"}",
            token);

    assertThat(response.statusCode())
        .as(
            "★ criterion 2: a department that does not exist must be REJECTED, not written: %s",
            response.body())
        .isEqualTo(400);
    assertThat(orgIdInDb(UUID.fromString(documentId)))
        .as("★ criterion 2: the rejected write must not have landed")
        .isNull();

    // A missing orgId is the same class of mistake (400), for the same reason.
    HttpResponse<String> empty =
        patch("/api/v1/knowledge-bases/" + kbId + "/documents/" + documentId + "/org", "{}", token);
    assertThat(empty.statusCode())
        .as("an omitted orgId is a 400, not a 500: %s", empty.body())
        .isEqualTo(400);
  }

  @Test
  void assigningToADocumentOfAnotherKbIsNotFound() throws Exception {
    String token = login("admin", "admin");
    String kbId = createKb(token);
    String otherKbId = createKb(token);
    String documentId = insertPendingDocument(otherKbId, "other", "Old Dept C");

    HttpResponse<String> response =
        patch(
            "/api/v1/knowledge-bases/" + kbId + "/documents/" + documentId + "/org",
            "{\"orgId\":\"" + ROOT_ORG + "\"}",
            token);

    assertThat(response.statusCode())
        .as("a document of ANOTHER kb is a 404 (not a 403 - that would confirm the id exists)")
        .isEqualTo(404);
  }

  // ---------------------------- fixtures and readings ----------------------------

  private int pendingTotal(String kbId, String token) throws Exception {
    String body =
        get("/api/v1/knowledge-bases/" + kbId + "/documents?orgAssignment=UNASSIGNED", token)
            .body();
    Matcher m = Pattern.compile("\"total\"\\s*:\\s*(\\d+)").matcher(body);
    assertThat(m.find()).as("no total in: %s", body).isTrue();
    return Integer.parseInt(m.group(1));
  }

  private String insertPendingDocument(String kbId, String name, String legacy) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_document (id, kb_id, scope_kind, scope_id, name, source, doc_type, org_id,"
            + " department_legacy, classification, status, char_count, segment_count, enabled,"
            + " created_at, updated_at)"
            + " values (?::uuid, ?::uuid, 'KB', ?::uuid, ?, 'local', '制度', null, ?, ?,"
            + " 'PENDING_EMBEDDING', 0, 0, true, now(), now())",
        id,
        kbId,
        kbId,
        name,
        legacy,
        ClassificationLevel.INTERNAL.name());
    return id;
  }

  private String orgIdInDb(UUID documentId) {
    return jdbc.queryForObject(
        "select org_id::text from kb_document where id = ?", String.class, documentId);
  }

  private String legacyInDb(UUID documentId) {
    return jdbc.queryForObject(
        "select department_legacy from kb_document where id = ?", String.class, documentId);
  }

  private String createKb(String token) throws Exception {
    String model =
        """
        {"name":"ASSIGN-ORG-%s","kind":"EMBEDDING","supplier":"LOCAL_INFERENCE",
         "baseModel":"bge-m3","apiBase":"http://localhost:11434/v1","dimension":768}\
        """
            .formatted(UUID.randomUUID().toString().substring(0, 8));
    Matcher m = ID.matcher(post("/api/v1/models", model, token).body());
    assertThat(m.find()).as("creating the embedding model failed").isTrue();
    String kb =
        """
        {"name":"ASSIGN-ORG-KB-%s","category":"制度规范","importType":"LOCAL",
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

  private HttpResponse<String> patch(String path, String body, String token) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
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
}
