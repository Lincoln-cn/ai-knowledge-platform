package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ★ 口径组断言（{@code #86}）：<strong>留缝已落地，本断言现在守的是「边界」—— 允许列存在，不允许版本链语义</strong>。
 *
 * <h3>★ 为什么这条断言从"没有版本"改写成了"没有版本链"</h3>
 *
 * <p>{@code #86} 的裁定是<strong>两段</strong>，而不是"不做"：① <strong>不做</strong>版本链与失效传播（属 {@code 0.5.x}
 * 之后）；② <strong>现在就留字段位置</strong>（{@code V55}）—— 晚留要补迁移，那时 {@code kb_document} 已经有数据了。 于是 {@code
 * kb_document} 上<strong>今天真的有</strong> {@code revision} 与 {@code supersedes_id} 两列 （口径 = {@code 02
 * §5.1.2}、{@code 12 §十三}），但<strong>没有任何代码读写它们</strong>（行为见证人 = {@code
 * DocumentRevisionSeamTest}：同名同库传两次 ⇒ 两条独立记录、revision 都是 1、supersedes_id 都是 NULL）。
 *
 * <p>★ 所以本类不能再写"迁移目录里不许出现 revision"—— 那与裁定直接冲突，红起来只是因为"断言过期了"，
 * 于是下一次有人要动它时唯一读得出的动作就是<strong>删掉这条断言</strong>。那才是真正的损失： 缺口还在，而唯一盯着它的见证人没了。改写后的形态把边界说清：
 *
 * <ul>
 *   <li><strong>允许</strong>：两列存在，且取值停在留缝这一侧（{@code revision int not null default 1}、 {@code
 *       supersedes_id uuid} 可空且<strong>无外键</strong>）；
 *   <li><strong>不允许</strong>：任何"真版本链"的痕迹 —— 反向指针（{@code superseded_by}）与链的命名 （{@code doc_version} /
 *       {@code version_chain} / {@code current_version}），见 {@link
 *       Caliber#DOCUMENT_REVISION_MARKERS}；
 *   <li><strong>不允许</strong>：实体上出现版本字段，或引用侧（{@code HitTestView.Item} / {@code
 *       SegmentSearchPort.Card}）出现版本维度。
 * </ul>
 *
 * <h3>它守的是哪一种读错</h3>
 *
 * <p>两列在库里，很容易让人以为"平台支持文档版本了"。而这个读错的后果很具体：以为有版本，就不会去查
 * "召回的是哪一版"，于是<strong>旧版与新版的分段一起进候选集，两条引用看起来都很权威</strong> —— 源制度已废止、平台仍按旧版本作答（{@code #86} 危害
 * ①）。这比"没有答案"更危险。
 *
 * <h3>★ 与 {@code 06} 的"工作流版本"划清界限（否则这条断言会误伤）</h3>
 *
 * <p>本仓确实有 {@code version}：{@code workflow.ts} 的 {@code getWorkflowVersionGraph} / {@code
 * rollbackWorkflow} —— 那是<strong>应用编排的产品能力</strong>（{@code 06:208}），不是知识库侧的文档版本。 所以本断言只扫 {@code
 * kb_} 前缀的表名与 {@code Document} 聚合，<strong>不</strong>扫 {@code as_} / {@code cv_}。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring</strong>。
 */
class DocumentVersionAbsenceTest {

  private static final String DOCUMENT = "cn.lincoln.aiplatform.knowledge.domain.Document";

  /**
   * {@code alter table kb_document add column <列> <定义>;}（{@link CaliberShape#migrationText()}
   * 已去注释）。
   */
  private static final Pattern KB_DOCUMENT_ADD_COLUMN =
      Pattern.compile("(?i)alter\\s+table\\s+kb_document\\s+add\\s+column\\s+(\\w+)\\s+([^;]*);");

  @Test
  void assertDocumentVersioningIsNotImplemented() {
    JavaClass document = Caliber.APP.get(DOCUMENT);

    // ① 实体形状：没有 supersedesId / supersededBy 这类"版本链"字段（★ revision / supersedes_id 只在**列**上，见 ④）
    assertThat(CaliberShape.instanceFields(document))
        .as(
            """
            ★ Document 的字段清单变了 —— 若新增的是版本链概念（supersedesId / supersededBy /
            documentRevision …），说明知识库侧开始有"同一份文档的新旧关系"了。
            ★ 注意 #86 的分界：`revision` / `supersedes_id` 是**列**（V55 留缝，无实体字段），
            列在基线里是允许的；实体上出现字段则说明有人开始**读写**它 —— 那是版本链的第一步，
            必须回头读 02 §5.1.2 与 12 §十三，把"旧引用按哪一版解析"写清楚，再更新基线。当前：%s\
            """,
            CaliberShape.render(Caliber.DOCUMENT_FIELDS))
        .containsExactlyInAnyOrderElementsOf(Caliber.DOCUMENT_FIELDS);

    // ② 库形状：kb_document 的列清单（与实体互相印证）
    assertThat(CaliberShape.tableColumns("kb_document"))
        .as(
            "★ kb_document 的列清单变了 —— 两列留缝（revision / supersedes_id）已在基线里；"
                + "若又多了 superseded_by / doc_version / current_version 这类名字，说明版本链开始落地")
        .containsExactlyInAnyOrderElementsOf(Caliber.DOCUMENT_COLUMNS);

    // ③ 否认读法：迁移目录里不许出现"真版本链"的痕迹（revision / supersedes 两词已因 #86 留缝而合法）
    String migrations = CaliberShape.migrationText();
    for (String marker : Caliber.DOCUMENT_REVISION_MARKERS) {
      assertThat(migrations)
          .as(
              "★ 迁移目录里出现了「%s」—— 知识库侧的**版本链**开始落地了。"
                  + "#86 明写「只留字段位置、不实现版本链与失效传播（属 0.5.x 之后）」："
                  + "版本链需要「引用按版本解析」的检索侧改动，与 MVP 无交集。"
                  + "（`revision` / `supersedes` 两个词因 V55 留缝已合法，故不再是标记；"
                  + "现在只抓反向指针与链的命名。）真要做时，12 必须先写清重新上传的两种后果，"
                  + "并回答「会话里那条旧引用指向哪一版」",
              marker)
          .doesNotContain(marker);
    }
  }

  /**
   * ★ 第四条读数（{@code #86} 留缝的<strong>边界</strong>）：两列允许存在，但取值必须停在"留缝"这一侧。
   *
   * <p>这条断言补前两条看不见的两件事：① 管"实体有没有字段"、③ 管"目录里有没有链的名字"，而 <strong>列还在、但被改成 {@code default 2}
   * 或加上自引用外键</strong>它们都看不见 —— 那正是留缝悄悄变成 "开始做版本链"的第一步。外键尤其关键：一加上它，"彻底删除旧版本"就会被挡住，而彻底删除正是 {@code
   * #75} 第二段要做的（{@code V55} 的注释把这条理由写在那里，两处不许各写一份）。
   *
   * <p>★ 为什么读"add column 的末尾定义"而不是 {@code contains} 全串：{@code contains} 会被注释、被别名、 被一行格式差异绕过；这里把
   * {@code alter table kb_document add column <名> <定义>;} 抽出来逐条比对， 多一个 {@code not null}、多一个 {@code
   * references} 都会红。
   */
  @Test
  void assertTheRevisionSeamKeepsItsBoundary() {
    Map<String, List<String>> added = kbDocumentAddColumnDefinitions();

    assertThat(added.getOrDefault("revision", List.of()))
        .as(
            """
            ★ kb_document.revision 的定义变了 —— #86 的留缝口径是 `int not null default 1`
            （02 §5.1.2、12 §十三）：默认 1 表达"这是第一版"，不是"没有版本"。
            改成 default 2 或别的业务值，说明有人开始把版本号写成真实语义了 —— 那一刻必须回来
            重读 02 §5.1.2 与 12 §十三，并同步改 DocumentRevisionSeamTest。当前：%s\
            """,
            CaliberShape.render(added.getOrDefault("revision", List.of())))
        .containsExactly("int not null default 1");

    assertThat(added.getOrDefault("supersedes_id", List.of()))
        .as(
            """
            ★ kb_document.supersedes_id 的定义变了 —— #86 的留缝口径是**可空、不加外键**的 `uuid`：
            加 not null 会让"首发文档"写不进去；加自引用外键会让"彻底删除旧版本"被外键挡住，
            而彻底删除正是 #75 第二段要做的（级联删还是删前断链，等真做版本链时再决定）。
            当前：%s\
            """,
            CaliberShape.render(added.getOrDefault("supersedes_id", List.of())))
        .containsExactly("uuid");

    assertThat(added)
        .as("★ 版本链的**反向指针**出现了：`superseded_by` 是「旧记录指向新记录」，一有它就说明在做链，而不是留一列")
        .doesNotContainKey("superseded_by");
  }

  /**
   * 迁移目录里对 {@code kb_document} 的每一次 {@code add column}：列名 → 定义文本（去注释、空白归一）。
   *
   * <p>★ 只认 {@code alter table kb_document add column}：{@code create table} 建表时的列不在内 —— 两列留缝都是
   * {@code V55} 的增量，正是本断言要盯的形态。
   */
  private static Map<String, List<String>> kbDocumentAddColumnDefinitions() {
    Matcher matcher = KB_DOCUMENT_ADD_COLUMN.matcher(CaliberShape.migrationText());
    Map<String, List<String>> definitions = new LinkedHashMap<>();
    while (matcher.find()) {
      definitions
          .computeIfAbsent(matcher.group(1).toLowerCase(Locale.ROOT), key -> new ArrayList<>())
          .add(matcher.group(2).trim().replaceAll("\\s+", " "));
    }
    return definitions;
  }

  /**
   * ★ 第二条读数：<strong>引用里也没有"版本"这个维度</strong>。
   *
   * <p>这是 {@code #86} 危害 ② 的落点：检索侧 {@code refs} 指向<strong>分段</strong>， 而分段属于某次入库的文档 ——
   * 但"哪一次"这件事<strong>在引用结构里根本不存在</strong>。 所以这里读两处结构的全量清单：
   *
   * <ul>
   *   <li>{@code HitTestView.Item}（检索回包里的命中项）；
   *   <li>{@code SegmentSearchPort.Card}（引用组装用的展示信息）。
   * </ul>
   *
   * <p>★ 关键：版本维度一定会从<strong>这两处之一</strong>进来（要让用户看见"这条引用来自哪一版"就得先把它读出来）。 所以它们的形状是"没做版本"的充分见证人之一 ——
   * 而不是随便挑两个类凑数。
   */
  @Test
  void assertReferencesCarryNoVersionDimension() {
    JavaClass item =
        Caliber.APP.get("cn.lincoln.aiplatform.retrieval.application.HitTestView$Item");
    JavaClass card =
        Caliber.APP.get("cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort$Card");

    assertThat(CaliberShape.instanceFields(item))
        .as(
            """
            ★ HitTestView.Item 的形状变了。它今天是"一条命中"，字段里没有"来自哪一版文档"。
            若新增了版本维度（documentRevision / supersedesId / sourceVersion …），说明引用开始
            能区分新旧版本了 —— 那是 #86 的位置被填上，先去 12 补口径（旧引用按哪一版解析）：%s\
            """,
            CaliberShape.render(CaliberShape.instanceFields(item)))
        .containsExactlyInAnyOrderElementsOf(
            List.of(
                "rank: int",
                "segmentId: UUID",
                "title: String",
                "docName: String",
                "kbName: String",
                "score: double",
                "content: String"));

    assertThat(CaliberShape.instanceFields(card))
        .as(
            """
            ★ SegmentSearchPort.Card 的形状变了。它是引用组装的唯一素材来源，
            今天给的是"标识（segmentId/documentId/kbId）+ 展示（title/content/docName/kbName）+ 敏感度（classification）"——
            其中 documentId/kbId 是**身份**（深链与"来源类型"）、classification 是**敏感度**（会话累计密级的来源），
            都不是**版本**：它们回答"这条引用来自哪个库/哪篇文档/有多敏感"，回答不了"来自哪一次上传"。
            若新增的是版本维度（documentRevision / supersedesId / sourceVersion …），
            同上去补 12 的口径（旧引用按哪一版解析）：%s\
            """,
            CaliberShape.render(CaliberShape.instanceFields(card)))
        .containsExactlyInAnyOrderElementsOf(
            List.of(
                "segmentId: UUID",
                "title: String",
                "content: String",
                "docName: String",
                "kbName: String",
                "documentId: UUID",
                "kbId: UUID",
                "classification: ClassificationLevel"));
  }
}
