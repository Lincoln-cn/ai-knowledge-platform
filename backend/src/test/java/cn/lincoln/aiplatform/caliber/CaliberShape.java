package cn.lincoln.aiplatform.caliber;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「口径组」六条断言共用的<strong>形状读数</strong>（第 16 周并行轨，issue {@code #81}–{@code #88}）。
 *
 * <h3>为什么这六个断言要有两个独立的见证人</h3>
 *
 * <p>口径组钉的是「<strong>当前没做这件事</strong>」。这类断言最容易写成<strong>恒绿</strong>—— 例如"扫一遍源码里有没有 {@code pii}
 * 这个词"，只要没人把字段起名叫 {@code pii} 就永远绿，与被测行为无关。 所以本类只提供两种**可逐字核对**的读数：
 *
 * <ol>
 *   <li>{@link #instanceFields} —— 实体类上<strong>真正声明的字段</strong>（名字 + 类型）， 由 ArchUnit 从 {@code
 *       target/classes} 的字节码里读，<strong>不读源码文本</strong>（源码里的注释提到某个名字不算数）；
 *   <li>{@link #tableColumns} —— <strong>迁移文件里真正建出来的列</strong>， 从 {@code classpath:db/migration/**}
 *       读（不依赖工作目录，本地与 CI 走同一份资源）。
 * </ol>
 *
 * <p>两者合起来才叫"位置是空的"：实体加了字段而迁移没加（或反之）都会被其中一条抓住， 而"顺手在某处加一列"正是本项目最贵的那类改动（{@code 13
 * §8.2}：会改数据形状的东西必须第一次就做对）。
 *
 * <h3>★ 为什么断言写成"全量列清单相等"而不是"不含某个名字"</h3>
 *
 * <p>「不含 X」这种断言在别人把 X 改名成 Y 之后<strong>照样绿</strong>，而缺口的实质没变。
 * 全量清单相等则相反：<strong>任何</strong>增量都会让它变红，逼人回来读这条断言在守什么。 这是刻意的摩擦 —— 口径组的全部价值就是"别把没做的事读成已经做了"。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring</strong>：两个读数都是纯读字节码与纯读资源文本， 所以它在没装 Docker 的本机与 CI 上跑出的是同一份结论。
 */
final class CaliberShape {

  /** 迁移文件的 classpath 根（{@code backend/src/main/resources/db/migration}）。 */
  private static final String MIGRATION_ROOT = "db/migration";

  /** 迁移文件的相对路径（相对 {@link #MIGRATION_ROOT}）。 */
  private static final List<String> MIGRATION_FILES =
      List.of(
          "platform/V1__platform_baseline.sql",
          "platform/V2__platform_event_publication.sql",
          "platform/V9__platform_enum_machine_codes.sql",
          "platform/V24__platform_dictionary.sql",
          "platform/V26__platform_kb_category_unify.sql",
          "identity/V3__identity_auth.sql",
          "identity/V4__identity_seed_demo_accounts.sql",
          "identity/V5__identity_login_lockout.sql",
          "identity/V6__identity_role_scope_and_permissions.sql",
          "identity/V7__identity_permission_source_of_truth_comment.sql",
          "identity/V10__identity_enum_machine_codes.sql",
          "identity/V25__identity_organization.sql",
          "identity/V28__identity_user_org_seed.sql",
          "modelcatalog/V8__modelcatalog_ai_model.sql",
          "modelcatalog/V11__modelcatalog_enum_machine_codes.sql",
          "knowledge/V12__knowledge_knowledge_base.sql",
          "knowledge/V13__knowledge_document.sql",
          "knowledge/V14__knowledge_document_parsed_content.sql",
          "knowledge/V15__knowledge_segment.sql",
          "knowledge/V16__knowledge_vec_embedding.sql",
          "knowledge/V17__knowledge_segment_title_length.sql",
          "knowledge/V18__knowledge_document_ocr_progress.sql",
          "knowledge/V19__knowledge_segment_content_tsv.sql",
          "knowledge/V20__knowledge_base_retrieval_params.sql",
          "knowledge/V21__knowledge_document_classification_rank.sql",
          "knowledge/V22__knowledge_segment_classification.sql",
          "knowledge/V23__knowledge_base_query_understanding.sql",
          "knowledge/V27__knowledge_document_org.sql",
          "assistant/V29__assistant_app.sql",
          "assistant/V30__assistant_access_credential.sql");

  private CaliberShape() {}

  // ────────────────────────── 读数①：实体的字段形状 ──────────────────────────

  /**
   * 实体类上<strong>真正声明的实例字段</strong>：{@code "字段名: 类型简单名"}。
   *
   * <p>★ 排除 {@code static} 与编译器合成字段（如内部类的 {@code this$0}）：它们与"这条记录存了什么"无关， 把它们算进来会让"加一个常量"也把断言弄红 ——
   * 那样红的理由就不可读了。
   *
   * <p>★ 类型取简单名：断言消息要能一眼读懂形状变化，全限定名在 diff 里没人看得下去。
   */
  static List<String> instanceFields(JavaClass type) {
    List<String> fields = new ArrayList<>();
    for (JavaField field : type.getFields()) {
      if (field.getModifiers().contains(JavaModifier.STATIC)
          || field.getModifiers().contains(JavaModifier.SYNTHETIC)) {
        continue;
      }
      fields.add(field.getName() + ": " + field.getRawType().getSimpleName());
    }
    return fields;
  }

  /**
   * 一个类的<strong>公开方法签名</strong>：{@code "方法名(参数类型简单名, …)"}。
   *
   * <p>用途：断言"某个能力今天压根没有入口"。它比"找一个叫 sync 的方法"强的地方在于 ——
   * 断言的是<strong>整张入口表</strong>，改个名字不会让它变绿，而任何新增入口都会让它变红。
   *
   * <p>★ 参数类型只取简单名（{@code UUID} 而不是全限定名）：断言消息要能读。 ★ 这里<strong>不</strong>数参数个数：个数的读数在 diff
   * 里看不出"换了个类型"，而类型正是这条断言要看的东西。
   */
  static List<String> publicSignatures(JavaClass type) {
    List<String> signatures = new ArrayList<>();
    for (JavaMethod method : type.getMethods()) {
      if (!method.getModifiers().contains(JavaModifier.PUBLIC)
          || method.getName().startsWith("$")) {
        continue;
      }
      StringBuilder params = new StringBuilder();
      for (JavaClass parameter : method.getRawParameterTypes()) {
        if (params.length() > 0) {
          params.append(", ");
        }
        params.append(parameter.getSimpleName());
      }
      signatures.add(method.getName() + "(" + params + ")");
    }
    return signatures;
  }

  // ────────────────────────── 读数②：迁移文件建出来的列 ──────────────────────────

  /**
   * 该表在<strong>全部迁移跑完之后</strong>的列清单（顺序 = 定义顺序）。
   *
   * <p>认得三种写法：{@code create table ... ( ... )} 里的列、{@code alter table ... add column ...}、 {@code
   * alter table ... rename column a to b}。★ 后两种必须认，否则"这个形状是 V25 加的"这类增量会读不出来。
   *
   * @param table 表名（小写）
   */
  static List<String> tableColumns(String table) {
    Map<String, LinkedHashSet<String>> tables = new LinkedHashMap<>();
    for (String file : MIGRATION_FILES) {
      applyMigrations(stripComments(read(file)), tables);
    }
    return List.copyOf(tables.getOrDefault(table, new LinkedHashSet<>()));
  }

  /**
   * 迁移文件里的<strong>标识符</strong>（去掉注释后的原文）。
   *
   * <p>用途：断言"某个名字在整个迁移目录里一次都没出现过"。 它比列清单更弱（会被注释与无关表命中），所以只用在"这个名字压根不该存在"这类否定断言上， 与 {@link
   * #tableColumns} 一起构成两条互补的读数。
   */
  static String migrationText() {
    StringBuilder all = new StringBuilder();
    for (String file : MIGRATION_FILES) {
      all.append(stripComments(read(file))).append('\n');
    }
    return all.toString();
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 把一份迁移的文本应用到"表 → 列"的累计视图上。
   *
   * <p>★ 只支持本仓实际用到的三种语句形态（{@code create table} / {@code add column} / {@code rename
   * column}）。刻意<strong>不写一个通用 SQL 解析器</strong>：那会在读不出来的时候静默给出空集合， 而"空集合"与"这张表没有这一列"在这条断言里是同一个结论 ——
   * 恒绿的经典成因。 认不出的语句一律由下面那两条断言（列清单相等）暴露出来，因为清单会缺项。
   */
  private static void applyMigrations(String sql, Map<String, LinkedHashSet<String>> tables) {
    Matcher create =
        Pattern.compile("(?i)create\\s+table\\s+(\\w+)\\s*\\(", Pattern.MULTILINE).matcher(sql);
    while (create.find()) {
      String table = create.group(1).toLowerCase();
      int bodyStart = create.end();
      int bodyEnd = endOfParentheses(sql, bodyStart);
      if (bodyEnd < 0) {
        continue;
      }
      LinkedHashSet<String> columns = tables.computeIfAbsent(table, key -> new LinkedHashSet<>());
      // create table 的正文按**顶层**逗号切：第一个词是列名（约束行以 constraint / primary / check 开头，被词表挡掉）。
      // ★ 必须按顶层切而不是 String.split(",")：CHECK 约束体里有嵌套括号与逗号
      //   （如 `check (status in ('A','B'))`），朴素切法会把枚举值当列名读进来 ——
      //   实测踩到过：列清单里凭空多了 status 的六个取值。
      for (String part : splitTopLevel(sql.substring(bodyStart, bodyEnd))) {
        String first = firstWord(part);
        if (first != null && !isConstraintKeyword(first)) {
          columns.add(first);
        }
      }
    }

    Matcher alter =
        Pattern.compile("(?i)alter\\s+table\\s+(\\w+)([^;]*);", Pattern.MULTILINE).matcher(sql);
    while (alter.find()) {
      String table = alter.group(1).toLowerCase();
      String body = alter.group(2);
      LinkedHashSet<String> columns = tables.computeIfAbsent(table, key -> new LinkedHashSet<>());
      Matcher add = Pattern.compile("(?i)add\\s+column\\s+(\\w+)", Pattern.MULTILINE).matcher(body);
      while (add.find()) {
        columns.add(add.group(1).toLowerCase());
      }
      Matcher rename =
          Pattern.compile("(?i)rename\\s+column\\s+(\\w+)\\s+to\\s+(\\w+)", Pattern.MULTILINE)
              .matcher(body);
      while (rename.find()) {
        columns.remove(rename.group(1).toLowerCase());
        columns.add(rename.group(2).toLowerCase());
      }
    }
  }

  /** {@code (} 之后第一个平衡的 {@code )} 的位置；找不到返回 -1。 */
  private static int endOfParentheses(String sql, int afterOpen) {
    int depth = 1;
    for (int i = afterOpen; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  /** 按<strong>顶层</strong>逗号切分（括号内的逗号不算分隔符）。 */
  private static List<String> splitTopLevel(String text) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(text.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(text.substring(start));
    return parts;
  }

  private static String firstWord(String text) {
    Matcher matcher = Pattern.compile("\\w+").matcher(text);
    return matcher.find() ? matcher.group().toLowerCase() : null;
  }

  /** 表级约束行的起始关键字（它们出现在 create table 的正文里，但不是列）。 */
  private static boolean isConstraintKeyword(String word) {
    return switch (word) {
      case "constraint", "primary", "unique", "foreign", "check", "exclude", "like" -> true;
      default -> false;
    };
  }

  /**
   * 去掉 {@code --} 行注释。
   *
   * <p>★ 必须去：本仓的迁移里大量注释<strong>正文就写着列名</strong>（"`path` 是物化路径…"），
   * 不去注释的文本扫描会把注释当成建表语句读。这是"读源码文本"那类断言最常见的假绿来源。
   */
  private static String stripComments(String sql) {
    return Pattern.compile("--[^\\n]*").matcher(sql).replaceAll("");
  }

  private static String read(String relative) {
    String resource = MIGRATION_ROOT + "/" + relative;
    try (InputStream in = CaliberShape.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("迁移文件不在 classpath 上：" + resource + " —— 本断言读的是资源，不是工作目录");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("读迁移文件失败：" + resource, e);
    }
  }

  /** 供断言消息拼接：把一个集合渲染成稳定的多行文本（diff 在消息里就能看）。 */
  static String render(Iterable<String> values) {
    StringBuilder text = new StringBuilder();
    Set<String> rendered = new LinkedHashSet<>();
    for (String value : values) {
      if (rendered.add(value)) {
        text.append("\n  ").append(value);
      }
    }
    return text.toString();
  }
}
