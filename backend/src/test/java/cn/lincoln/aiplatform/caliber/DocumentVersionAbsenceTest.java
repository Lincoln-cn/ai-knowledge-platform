package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★ 口径组断言（{@code #86}）：<strong>知识库侧没有"文档版本"概念 —— 同一份文档的多次上传之间没有任何关系</strong>。
 *
 * <h3>它断言的是"当前没做这件事"，所以它将来必须被刻意改掉</h3>
 *
 * <p>{@code 12} 新增的那句口径写的是"源文档更新时的<strong>现行做法与其代价</strong>"：重新上传会产生
 * <strong>并存的新旧分段</strong>（检索可能召回旧版本）或<strong>覆盖后旧引用失效</strong>（会话里还显示着）——
 * 两种结局都错。这条断言把"我们知道自己没做"钉在地上，因为这一条的危害形态是本项目最贵的那一类：
 *
 * <blockquote>
 *
 * 源制度已废止，平台仍按旧版本作答，<strong>而引用看着完全权威</strong>（编号、来源、溯源都对）—— 比"没有答案"更危险（{@code #86} 危害 ①）。
 *
 * </blockquote>
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

  @Test
  void assertDocumentVersioningIsNotImplemented() {
    JavaClass document = Caliber.APP.get(DOCUMENT);

    // ① 实体形状：没有 revision / supersedes 这类"版本链"字段
    assertThat(CaliberShape.instanceFields(document))
        .as(
            """
            ★ Document 的字段清单变了 —— 若新增的是版本/修订概念（revision / supersedesId /
            supersededBy …），说明知识库侧开始有"同一份文档的新旧关系"了。
            那一刻必须回头读 12 的那句口径（重新上传的两种后果）与 02 的字段位置，
            把"旧引用按哪一版解析"写清楚，再更新基线。当前：%s\
            """,
            CaliberShape.render(Caliber.DOCUMENT_FIELDS))
        .containsExactlyInAnyOrderElementsOf(Caliber.DOCUMENT_FIELDS);

    // ② 库形状：kb_document 的列清单（与实体互相印证）
    assertThat(CaliberShape.tableColumns("kb_document"))
        .as("★ kb_document 的列清单变了 —— 尤其注意 revision / supersedes_id / doc_version 这类名字")
        .containsExactlyInAnyOrderElementsOf(Caliber.DOCUMENT_COLUMNS);

    // ③ 否认读法：迁移目录里不许出现知识库侧的版本痕迹
    String migrations = CaliberShape.migrationText();
    for (String marker : Caliber.DOCUMENT_REVISION_MARKERS) {
      assertThat(migrations)
          .as(
              "★ 迁移目录里出现了「%s」—— 知识库侧的版本概念开始落地了。"
                  + "#86 明写「不实现版本链与失效传播（属 0.5.x 之后）」：它需要「引用按版本解析」的"
                  + "检索侧改动，与 MVP 无交集。真要做时，12 必须先写清重新上传的两种后果，"
                  + "并回答「会话里那条旧引用指向哪一版」",
              marker)
          .doesNotContain(marker);
    }
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
            今天给的是"标识（segmentId/documentId/kbId）+ 展示（title/content/docName/kbName）"——
            其中后两项是**身份**（深链与"来源类型"），不是**版本**：它们回答"这条引用来自哪个库/哪篇文档"，
            回答不了"来自哪一次上传"。若新增了版本维度（documentRevision / supersedesId / sourceVersion …），
            同上去补口径：%s\
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
                "kbId: UUID"));
  }
}
