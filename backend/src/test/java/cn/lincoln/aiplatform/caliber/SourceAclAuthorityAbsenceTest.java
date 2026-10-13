package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★ 口径组断言（{@code #81}）：<strong>权限的权威源只有"平台内"这一个，源系统的 ACL 没有进口</strong>。
 *
 * <h3>它断言的是"当前没做这件事"，所以它将来必须被刻意改掉</h3>
 *
 * <p>{@code 13 §十二} 新增的那一节写的是：<strong>平台内标注是唯一权威源，源系统 ACL 今天不参与判定</strong>。
 * 文档一旦被读成"权限已经跟着源系统走了"，就会冒出一条最危险的假设 —— <em>"这个人在源系统看不到，所以他在平台里也搜不到"</em>（{@code #81} 危害 ①）。
 * 这条断言把这个假设钉在地上：<strong>它今天是假的</strong>。
 *
 * <h3>两个独立的见证人（缺任一个，断言都能靠改名糊过去）</h3>
 *
 * <ol>
 *   <li><strong>实体形状</strong>：{@code Document} 声明的实例字段全量清单 —— 加一个 {@code sourceAcl} 字段就红；
 *   <li><strong>库形状</strong>：{@code kb_document} 跑完全部迁移之后的列全量清单 —— 加一列就红；
 *   <li><strong>否认读法</strong>：整个迁移目录里不许出现任何"源系统授权依据"的名字 —— 挡住"换个名字加进来"（例如 {@code acl_ref} / {@code
 *       connector_id}）。
 * </ol>
 *
 * <p>★ 红的时候要做什么：回去读 {@code 13} 那一节口径，把<strong>冲突时谁赢</strong>（源 ACL 与平台密级/组织 谁优先）写进文档，再更新 {@link
 * Caliber} 里的基线 —— 而不是把断言删掉。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring、不需要桩模型</strong>：读的是编译产物与迁移资源。
 */
class SourceAclAuthorityAbsenceTest {

  private static final String DOCUMENT = "cn.lincoln.aiplatform.knowledge.domain.Document";

  @Test
  void assertSourceAclSyncIsNotImplemented() {
    JavaClass document = Caliber.APP.get(DOCUMENT);

    // ① 实体形状：kb_document 今天存的是"平台内标注"，没有任何源系统授权依据
    assertThat(CaliberShape.instanceFields(document))
        .as(
            """
            ★ kb_document 的字段清单变了 —— 如果新增的字段是"源系统授权依据"（sourceAcl / aclRef /
            connectorId …），说明平台开始记录源权限了。那一刻必须回头读 13 的那一节口径，把
            **冲突时谁赢**（源 ACL 与平台密级/组织谁优先）写清楚，并更新 Caliber 的基线。
            当前基线（今天的实际形状，source 只是来源渠道、不是权限依据）：%s\
            """,
            CaliberShape.render(Caliber.DOCUMENT_FIELDS))
        .containsExactlyInAnyOrderElementsOf(Caliber.DOCUMENT_FIELDS);

    // ② 库形状：迁移文件里建出来的列（与实体互相印证，"只改一边"也会被抓住）
    assertThat(CaliberShape.tableColumns("kb_document"))
        .as(
            """
            ★ kb_document 的列清单变了（跑完全部迁移之后的实际形状）。新增列若与"源系统"有关
            （source_doc_id / source_revision / source_acl …），那就是 #81 的位置开始被填上 ——
            请先确认 13 的口径里有"冲突时谁赢"，再更新基线。\
            """)
        .containsExactlyInAnyOrderElementsOf(Caliber.DOCUMENT_COLUMNS);

    // ③ 否认读法：整个迁移目录里连名字都不该出现
    String migrations = CaliberShape.migrationText();
    for (String marker : Caliber.SOURCE_ACL_MARKERS) {
      assertThat(migrations)
          .as(
              "★ 迁移目录里出现了「%s」—— 源系统授权依据的进口被打开了。"
                  + "#81 的处置是「先定口径再实现」，所以出现它的同时，13 必须已经有"
                  + "「谁是权威源 / 冲突时谁赢 / 密级与源 ACL 的关系」这一节",
              marker)
          .doesNotContain(marker);
    }

    // ④ 今天真正决定"能看见什么"的，是平台内那一条谓词 —— 它一个源系统字段都不读
    List<String> scopeFilterFields =
        CaliberShape.instanceFields(
            Caliber.APP.get("cn.lincoln.aiplatform.platform.access.ScopeFilter"));
    assertThat(scopeFilterFields)
        .as(
            """
            ★ 可见性判定的输入变了。ScopeFilter 是"能看见哪些"的唯一入口（07 §3.3），
            它今天只吃平台内的东西（me / scope / myRoles / maxClassification / clLabels / myOrgIds）
            —— 一个源系统标识都没有。若新增了源系统维度，那就是权限的权威源发生了变更：%s\
            """,
            CaliberShape.render(scopeFilterFields))
        .containsExactlyInAnyOrderElementsOf(
            List.of(
                "me: UUID",
                "scope: DataScope",
                "myRoles: Set",
                "maxClassification: ClassificationLevel",
                "clLabels: Set",
                "myOrgIds: Set"));
  }
}
