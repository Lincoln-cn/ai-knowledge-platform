package cn.lincoln.aiplatform.caliber;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * 口径组六条断言共用的<strong>字段与列基线</strong>（issue {@code #81}–{@code #88}）。
 *
 * <p>★ 每个常量都是「<strong>跑完全部迁移、按当前源码编译之后</strong>该表的实际形状」逐字抄下来的。
 * 它们不是"期望的设计"，而是"今天的事实"：<strong>任何一处增量都会让对应的断言变红</strong>， 而红的那一刻正是这个缺口被填上的时刻 —— 那时必须回来读 {@code
 * 13}/{@code 02}/{@code 07} 里那一节口径， 把「谁赢」写清楚，再更新这里的基线。
 *
 * <p>★ 为什么不写成"不许含 XX"：见 {@link CaliberShape} 的类注释。
 */
final class Caliber {

  /** 被测应用的主类字节码（从 {@code target/classes} 读，不读源码文本）。 */
  static final JavaClasses APP = new ClassFileImporter().importPackages("cn.lincoln.aiplatform");

  // ────────────────────────── #81 · 连接器与 ACL 同步 ──────────────────────────

  /**
   * {@code kb_document} 的字段（{@code knowledge/domain/Document.java}）。
   *
   * <p>★ 基线里<strong>没有</strong>任何"源系统的授权依据"：没有 {@code sourceAcl} / {@code sourceAclRef} / {@code
   * sourcePermissions}。已有的 {@code source} 是契约 {@code DocumentItem.source} 那个
   * <strong>来源渠道</strong>串（今天恒为 {@code local}，见 {@code V13} 的列注释），它<strong>不是</strong>权限依据。
   */
  static final java.util.List<String> DOCUMENT_FIELDS =
      java.util.List.of(
          "id: UUID",
          "kbId: UUID",
          "name: String",
          "source: String",
          "docType: String",
          "orgId: UUID",
          "departmentLegacy: String",
          "classification: ClassificationLevel",
          "status: DocumentStatus",
          "failReason: String",
          "failHint: String",
          "parsedContent: String",
          "charCount: long",
          "segmentCount: int",
          "processedSegments: int",
          "ocrTotalPages: int",
          "ocrDonePages: int",
          "ocrPageCount: int",
          "enabled: boolean",
          "createdAt: Instant",
          "createdBy: UUID",
          "updatedAt: Instant",
          "updatedBy: UUID",
          "deletedAt: Instant");

  /** {@code kb_document} 跑完全部迁移之后的列（{@code V13} 建表 + {@code V14/V18/V21/V27} 增量）。 */
  static final java.util.List<String> DOCUMENT_COLUMNS =
      java.util.List.of(
          "id",
          "kb_id",
          "name",
          "source",
          "doc_type",
          "classification",
          "status",
          "fail_reason",
          "fail_hint",
          "char_count",
          "segment_count",
          "processed_segments",
          "enabled",
          "created_at",
          "created_by",
          "updated_at",
          "updated_by",
          "deleted_at",
          "parsed_content",
          "ocr_total_pages",
          "ocr_done_pages",
          "ocr_page_count",
          "classification_rank",
          "department_legacy",
          "org_id");

  /**
   * {@code #81} 的禁用词：源系统授权依据的痕迹。
   *
   * <p>用在"整个迁移目录里一次都没出现过"这条否定断言上。
   */
  static final java.util.List<String> SOURCE_ACL_MARKERS =
      java.util.List.of(
          "source_acl", "source_acl_ref", "acl_sync", "connector_id", "source_doc_id");

  // ────────────────────────── #82 · 目录同步（组织的权威源） ──────────────────────────

  /**
   * {@code iam_user} 的字段（{@code identity/domain/User.java}）。
   *
   * <p>★ 基线里没有 {@code externalId} / {@code directorySource}：人员只能由平台内建账号产生。
   */
  static final java.util.List<String> USER_FIELDS =
      java.util.List.of(
          "id: UUID",
          "username: String",
          "displayName: String",
          "passwordHash: String",
          "roleId: UUID",
          "orgId: UUID",
          "status: UserStatus",
          "mustChangePassword: boolean",
          "lastLoginAt: Instant",
          "failedLoginAttempts: int",
          "lockedUntil: Instant",
          "createdAt: Instant",
          "updatedAt: Instant",
          "deletedAt: Instant");

  /** {@code iam_user} 跑完全部迁移之后的列（{@code V3} 建表 + {@code V5/V25} 增量）。 */
  static final java.util.List<String> USER_COLUMNS =
      java.util.List.of(
          "id",
          "username",
          "display_name",
          "password_hash",
          "role_id",
          "status",
          "must_change_password",
          "last_login_at",
          "created_at",
          "created_by",
          "updated_at",
          "updated_by",
          "deleted_at",
          "failed_login_attempts",
          "locked_until",
          "org_id");

  /**
   * {@code iam_org} 的字段（{@code identity/domain/Org.java}）。
   *
   * <p>★ {@code code} 是<strong>平台内</strong>的稳定机器码，不是外部目录的 {@code externalId} —— 这条区分是 {@code #82}
   * 的全部要点：目录同步要做的是"把外部 id 映射到 {@code code}"， 而今天连这个映射的位置都没有。
   */
  static final java.util.List<String> ORG_FIELDS =
      java.util.List.of(
          "id: UUID",
          "parentId: UUID",
          "code: String",
          "name: String",
          "path: String",
          "sort: int",
          "enabled: boolean",
          "createdAt: Instant",
          "createdBy: UUID",
          "updatedAt: Instant",
          "updatedBy: UUID");

  /** {@code iam_org} 跑完全部迁移之后的列（{@code V25} 一次建齐）。 */
  static final java.util.List<String> ORG_COLUMNS =
      java.util.List.of(
          "id",
          "parent_id",
          "code",
          "name",
          "path",
          "sort",
          "enabled",
          "created_at",
          "created_by",
          "updated_at",
          "updated_by");

  /**
   * 外部目录标识的<strong>禁用词</strong>（{@code #82}）。
   *
   * <p>用在"整个迁移目录里一次都没出现过"这条否定断言上：目录同步一定会引入这几个名字之一， 一旦出现就说明口径该回头核一遍（谁是权威源、离职调岗怎么收敛）。
   */
  static final java.util.List<String> DIRECTORY_ID_MARKERS =
      java.util.List.of("external_id", "external_code", "ldap_dn", "scim_id", "directory_source");

  // ────────────────────────── #86 · 文档版本 ──────────────────────────

  /**
   * {@code #86} 的禁用词：知识库侧的"版本/修订"痕迹。
   *
   * <p>★ 与 {@code 06} 的<strong>工作流版本</strong>区分开 —— 后者是产品能力（{@code as_assistant} 那一侧， 契约里确实有 {@code
   * version}），本条只管知识库侧（文档与引用）。所以断言只扫 {@code kb_} 前缀的表。
   */
  static final java.util.List<String> DOCUMENT_REVISION_MARKERS =
      java.util.List.of("revision", "supersedes", "superseded_by", "doc_version");

  /**
   * {@code HitTestView.Trace} 的字段（{@code retrieval/application/HitTestView.java}，{@code 10 §7.1}
   * 的埋点口径）。
   *
   * <p>★ 基线里<strong>没有</strong> {@code configVersion} / {@code promptVersion} / {@code
   * experimentGroup}： 轨迹能回答"这层做了什么"，但回答不了"这是哪一版配置做出来的"（{@code #88}）。
   */
  static final java.util.List<String> TRACE_FIELDS =
      java.util.List.of(
          "subQuestionCount: int",
          "queryCount: int",
          "pathCount: int",
          "degradeLevel: int",
          "degradeReason: String",
          "truncated: boolean",
          "elapsedMs: long",
          "hopTriggered: boolean",
          "hopRounds: int",
          "hopStopReason: String",
          "hopNewSegments: int");

  /**
   * {@code #88} 的禁用词：对照实验/灰度一定会引入的维度名。
   *
   * <p>断言范围是<strong>检索模块</strong>（{@code 10 §10.3} 的 V1 清单所在册对应的代码）。
   */
  static final java.util.List<String> EXPERIMENT_MARKERS =
      java.util.List.of("configVersion", "promptVersion", "experimentGroup", "abVariant");

  private Caliber() {}
}
