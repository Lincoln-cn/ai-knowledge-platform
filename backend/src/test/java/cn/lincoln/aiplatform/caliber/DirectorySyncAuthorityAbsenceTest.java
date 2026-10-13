package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★ 口径组断言（{@code #82}）：<strong>组织与人员的权威源只有"平台内手工维护"这一个，目录服务没有进口</strong>。
 *
 * <h3>它断言的是"当前没做这件事"，所以它将来必须被刻意改掉</h3>
 *
 * <p>{@code 07} 新增的那一节写的是：{@code iam_org} / {@code iam_user} 的权威源是<strong>平台内</strong>， 目录同步（AD /
 * LDAP / SCIM）排在 {@code 0.5.x} 之后。这条断言把它钉在地上，因为这里有一个非常贵的混淆：
 *
 * <blockquote>
 *
 * {@code 04:79} 与 {@code 03:570} 写的是"SSO <strong>按需</strong>推迟（内网账号密码足够）" ——
 * 那是<strong>登录方式</strong>；而"有哪些人、谁在哪个部门"是<strong>权限模型的输入</strong>。 把两者当一件事，就会得出"SSO 推迟了 ⇒
 * 组织也是平台内的，没问题"这个错误结论 （{@code #82} 根因）。
 *
 * </blockquote>
 *
 * <h3>★ 为什么"组织是准的"这件事值得一条断言</h3>
 *
 * <p>部门数据权限（{@code 07 §十一} 三个消费位）的正确性<strong>完全依赖组织树是准的</strong>：
 * 树一歪，"共享给技术部"就把库给错了人；而人离职/调岗后组织树不会自己变 —— 那正是等保对"访问控制"要求的那一条（{@code #82} 危害 ③）。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring</strong>。
 */
class DirectorySyncAuthorityAbsenceTest {

  private static final String USER = "cn.lincoln.aiplatform.identity.domain.User";
  private static final String ORG = "cn.lincoln.aiplatform.identity.domain.Org";

  @Test
  void assertDirectorySyncIsNotImplemented() {
    JavaClass user = Caliber.APP.get(USER);
    JavaClass org = Caliber.APP.get(ORG);

    // ① 人员形状：iam_user 上没有"外部目录 id"
    assertThat(CaliberShape.instanceFields(user))
        .as(
            """
            ★ iam_user 的字段清单变了 —— 若新增的是外部目录标识（externalId / directorySource …），
            说明账号开始有"平台外"的来源了。那一刻必须回头读 07 的那一节口径：
            **组织的权威源是谁**、离职与调岗时权限如何收敛，并把它们写进文档后再更新基线。
            当前基线（今天的实际形状）：%s\
            """,
            CaliberShape.render(Caliber.USER_FIELDS))
        .containsExactlyInAnyOrderElementsOf(Caliber.USER_FIELDS);

    // ② 组织形状：iam_org 上没有"外部目录 id"（code 是平台内的稳定码，不是 externalId）
    assertThat(CaliberShape.instanceFields(org))
        .as(
            """
            ★ iam_org 的字段清单变了。注意区分两者：**code 是平台内的身份**（改名不改码，进 grants
            与审计），**externalId 是"这个节点在 AD 里是谁"** —— 后者今天不存在。若新增了它，
            说明目录同步的映射位置开始被填上：先确认 07 有"冲突时谁赢"，再更新基线。当前：%s\
            """,
            CaliberShape.render(Caliber.ORG_FIELDS))
        .containsExactlyInAnyOrderElementsOf(Caliber.ORG_FIELDS);

    // ③ 库形状：两张表跑完全部迁移之后的列（与实体互相印证）
    assertThat(CaliberShape.tableColumns("iam_user"))
        .as("★ iam_user 的列清单变了（跑完全部迁移之后的实际形状）—— 新增列若与外部目录有关，先补口径")
        .containsExactlyInAnyOrderElementsOf(Caliber.USER_COLUMNS);
    assertThat(CaliberShape.tableColumns("iam_org"))
        .as("★ iam_org 的列清单变了 —— 尤其注意 external_code / directory_source 这类名字")
        .containsExactlyInAnyOrderElementsOf(Caliber.ORG_COLUMNS);

    // ④ 否认读法：整个迁移目录里连"外部目录标识"的名字都不该出现
    String migrations = CaliberShape.migrationText();
    for (String marker : Caliber.DIRECTORY_ID_MARKERS) {
      assertThat(migrations)
          .as(
              "★ 迁移目录里出现了「%s」—— 外部目录的进口被打开了。"
                  + "#82 的处置是「先定口径再实现」：出现它的同时，07 必须已经有"
                  + "「组织的权威源是谁 / 离职调岗如何收敛 / 与 SSO 的区别」这一节",
              marker)
          .doesNotContain(marker);
    }
  }

  /**
   * ★ 第二条读数：组织树的<strong>形状</strong>里没有"同步进来的证据"。
   *
   * <p>这条与上面那条是两件事：上面看的是"有没有外部 id 这个位置"，这里看的是 <strong>{@code Org} 的构造入口收的是什么</strong> ——
   * 它今天收的是人手工填的 {@code code / name}（{@code OrgService.create} 由 HTTP 请求驱动），没有任何"批量应用一份目录快照"的入口。
   *
   * <p>★ 为什么这条不写成"找一个叫 sync 的方法"：那种断言靠名字，改个名就绿了。
   * 这里断言的是<strong>搬运本身不存在</strong>：把整棵目录树交给一个入口那件事，今天没有对应的能力。
   */
  @Test
  void assertOrgTreeHasNoBulkDirectoryApplyEntryPoint() {
    JavaClass orgService = Caliber.APP.get("cn.lincoln.aiplatform.identity.application.OrgService");
    List<String> publicMethods = CaliberShape.publicSignatures(orgService);

    assertThat(publicMethods)
        .as(
            """
            ★ OrgService 的公开方法清单变了。它今天只有"人手工改组织树"这一个入口
            （create / update / move / list …），**没有**"把一份目录快照整体应用进来"这类能力。
            若新增了后者，说明组织开始有第二个权威源 —— 那就是 #82 的位置被填上了：
            先去 07 写清"平台内维护与目录同步并存时谁是权威 / 冲突时谁赢"，再更新基线。当前：%s\
            """,
            CaliberShape.render(publicMethods))
        .containsExactlyInAnyOrderElementsOf(
            List.of(
                "create(UUID, String, String, Integer, UUID)",
                "delete(UUID)",
                "list()",
                "move(UUID, UUID, UUID)",
                "tree()",
                "update(UUID, String, Integer, Boolean, UUID)"));
  }
}
