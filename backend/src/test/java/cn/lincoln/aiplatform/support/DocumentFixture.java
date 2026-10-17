package cn.lincoln.aiplatform.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试夹具里唯一一处直接写 kb_document 的地方（#48）。
 *
 * <p>T300（V27）把 kb_document.department 改名为 department_legacy 时，主代码全绿、编译全绿， 却有 52 条测试红 —— 真因是测试夹具用裸
 * SQL 直接写表：绕过实体与仓储 ⇒ 表结构一变， 编译器一句话都不说，只在运行时以 BadSqlGrammarException 爆炸，而爆炸点离改动点很远。
 *
 * <p>更危险的一面（#48 危害 2）：如果某次改动只影响夹具而主代码没变，那一批测试根本不会提醒你 —— 它们测的是自己拼的 SQL，不是产品路径。夹具与产品是两份真相。
 *
 * <p>列清单只在这里出现一次：DocumentFixtureGuardTest 在守"全库命中数只许下降"。
 *
 * <p>为什么夹具不能全走产品路径（DocumentService）：有几类形状产品路径拒绝或不产生 —— 密级高于库上限的存量文档、org_id 为空而 department_legacy
 * 非空的回填残留、 会话域文档（kb_id 为空）、任意 scope_kind/owner_id 组合。走服务层就造不出来 ⇒ 保留裸 SQL，但收成一处。
 *
 * <p>外键：V27 给 org_id 加了外键 ⇒ 夹具不能随便塞 UUID。V25 种子里只有 ROOT 是固定 UUID。
 *
 * <p>本类的来历：第一版把 16 处一次性全迁过来，实测 3 红 + 16 错（DocumentScopeShapeTest 出现 expected: 1 but was: 0
 * 这种语义回归，另有 18 个 error 全是 kbId 为 null 的 NPE —— 会话域文档没有库行）。那一版整批回退，只留闸门。教训：这是一次逐类的活，不是一次批量替换。
 */
public final class DocumentFixture {

  /** V25 种子里唯一固定 UUID 的部门（ROOT）。 */
  public static final String ROOT_ORG_ID = "00000000-0000-4000-8000-000000000001";

  /** 常规段：scope_kind 恒 'KB'、无 owner_id。 */
  private static final String INSERT =
      "insert into kb_document (id, kb_id, scope_kind, scope_id, name, source, doc_type, org_id,"
          + " department_legacy, classification, status, char_count, segment_count, enabled,"
          + " created_at, updated_at)"
          + " values (?::uuid, ?::uuid, 'KB', ?::uuid, ?, 'local', ?, ?::uuid, ?, ?, ?,"
          + " 0, 0, true, now(), now())";

  /**
   * 扩展列段：scope_kind / scope_id / owner_id 显式给，且 kb_id 允许为空（会话域文档没有库行）。
   *
   * <p>★★ {@code cast(? as uuid)} 而不是裸 {@code ?}（本批实测踩到）：这一段的实参可能是 {@code String}（调用方手上常有 UUID
   * 与串两种形态）也可能是 {@code null}，而 {@code kb_id} 列是 {@code uuid} ⇒ 裸 {@code ?} 会被 PG 读成 {@code
   * character varying}，报 {@code column "kb_id" is of type uuid but expression is of type character
   * varying}。 ★ {@code cast} 对 {@code null} 与 UUID 两种实参都安全，一个写法覆盖两种调用方。
   */
  private static final String INSERT_WITH_SCOPE =
      "insert into kb_document (id, kb_id, scope_kind, scope_id, owner_id, name, source, doc_type,"
          + " org_id, department_legacy, classification, status, char_count, segment_count,"
          + " enabled, created_at, updated_at) values (?::uuid, cast(? as uuid), ?, ?::uuid,"
          + " cast(? as uuid), ?, 'local', ?, ?::uuid, ?, ?, ?, 0, 0, true, now(), now())";

  private DocumentFixture() {}

  /** 最常用形态：制度 + 归属 ROOT + 无存档部门 + PENDING_EMBEDDING（建档刚完成）。 */
  public static UUID insertPending(
      JdbcTemplate jdbc, String kbId, String name, String classification) {
    return insert(jdbc, kbId, name, classification, Options.defaults());
  }

  /** 造一篇 SUCCEEDED 的文档（分段夹具最常用的形状：检索要能命中它）。 */
  public static UUID insertSucceeded(
      JdbcTemplate jdbc, String kbId, String name, String classification) {
    return insert(jdbc, kbId, name, classification, Options.defaults().withStatus("SUCCEEDED"));
  }

  /** 造一篇文档（可变项走 Options）。 */
  public static UUID insert(
      JdbcTemplate jdbc, String kbId, String name, String classification, Options options) {
    Options opts = options == null ? Options.defaults() : options;
    UUID id = UUID.randomUUID();
    jdbc.update(
        INSERT,
        id.toString(),
        kbId,
        kbId,
        name,
        opts.docType(),
        opts.orgId(),
        opts.departmentLegacy(),
        classification,
        opts.status());
    return id;
  }

  /** 扩展列变体：显式给归属形状。kbId 允许 null（会话域文档没有库行）。 */
  public static UUID insertWithScope(
      JdbcTemplate jdbc,
      UUID kbId,
      String scopeKind,
      UUID scopeId,
      UUID ownerId,
      String name,
      String orgId,
      String classification) {
    return insertWithScope(
        jdbc, UUID.randomUUID(), kbId, scopeKind, scopeId, ownerId, name, orgId, classification);
  }

  /**
   * 扩展列变体 + <strong>调用方指定文档 id</strong>。
   *
   * <p>★ 为什么要这个重载（本批实测踩到）：有一类夹具<strong>先声明常量 id、再拿它去断言</strong> （{@code DocumentScopeShapeTest}
   * 的形状就是"我造了这几条已知 id 的文档，然后验列表里恰好是它们"）。 夹具自己生成 id 会让那些断言拿到一个对不上的值 ⇒ 整批红。★ "我要自己管 id"这件事在调用点上
   * 必须一眼可见，所以单独一个重载，而不是给默认那条塞一个可空参数。
   */
  public static UUID insertWithScope(
      JdbcTemplate jdbc,
      UUID id,
      UUID kbId,
      String scopeKind,
      UUID scopeId,
      UUID ownerId,
      String name,
      String orgId,
      String classification) {
    jdbc.update(
        INSERT_WITH_SCOPE,
        id.toString(),
        kbId == null ? null : kbId.toString(),
        scopeKind,
        scopeId == null ? null : scopeId.toString(),
        ownerId == null ? null : ownerId.toString(),
        name,
        "制度",
        orgId,
        null,
        classification == null ? "INTERNAL" : classification,
        "PENDING_EMBEDDING");
    return id;
  }

  /** 按 code 查一个真实存在的部门 id（V25 种子；#48 处置 2 的那个助手）。 */
  public static String orgIdByCode(JdbcTemplate jdbc, String code) {
    String id =
        jdbc.queryForObject("select id::text from iam_org where code = ?", String.class, code);
    if (id == null) {
      throw new IllegalStateException("V25 种子里没有 code=" + code + " 的部门 —— 夹具的前提变了");
    }
    return id;
  }

  /** 可变项（record + withXxx：调用点上能读出"我这次改的是哪几项"）。 */
  public record Options(String docType, String orgId, String departmentLegacy, String status) {

    public static Options defaults() {
      return new Options("制度", ROOT_ORG_ID, null, "PENDING_EMBEDDING");
    }

    public Options withOrgId(String value) {
      return new Options(docType, value, departmentLegacy, status);
    }

    public Options withDepartmentLegacy(String value) {
      return new Options(docType, orgId, value, status);
    }

    public Options withDocType(String value) {
      return new Options(value, orgId, departmentLegacy, status);
    }

    public Options withStatus(String value) {
      return new Options(docType, orgId, departmentLegacy, value);
    }
  }
}
