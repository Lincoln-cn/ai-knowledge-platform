package cn.lincoln.aiplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.identity.application.OrgService;
import cn.lincoln.aiplatform.identity.domain.Org;
import cn.lincoln.aiplatform.identity.domain.OrgRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 组织机构树（第 19 周 · 形状收口，{@code 07 §十}）—— 真 PG，纯 SQL 夹具。
 *
 * <h3>这个类守的是"不报错但错"的那几条</h3>
 *
 * <ol>
 *   <li>★ <strong>路径前缀不许跨部门</strong>：物化路径最容易犯的错是前缀没有结尾斜杠 —— 那样 {@code /ROOT/AD/} 会 match 到 {@code
 *       /ROOT/ADMIN/}，症状是"某个部门看到了另一个部门"， 而<strong>查询照样跑</strong>。{@link
 *       #subtreePrefixNeverCrossesDepartments()} 就是它的哨兵。
 *   <li><strong>不许移进自己的子树</strong>：会形成父子环 —— 树遍历与"含下级"的判定立刻失去意义，也不报错。
 *   <li><strong>有下级或有用户的部门不许删</strong>：删了会留孤儿（用户 {@code org_id} 指向不存在的行）。 要下线请用停用（停用 ≠ 撤销）。
 * </ol>
 *
 * <p>★ 种子直接来自 `V11`：一棵 ROOT + 八个部门的树，与前端 `DEPARTMENT_SUGGESTIONS` 同值 ——
 * 那条"两边名字不一致"的症状（演示时好用、部署后不一样）由迁移保证，这里只验行为。
 */
@SpringBootTest
@Testcontainers
class OrgServiceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID ACTOR = UUID.randomUUID();

  @Autowired OrgService orgs;

  @Autowired OrgRepository repository;

  @Autowired JdbcTemplate jdbc;

  @Test
  void seedGivesARootWithTheEightDepartments() {
    List<OrgService.OrgNode> tree = orgs.tree();

    assertThat(tree).as("只有一个根").hasSize(1);
    assertThat(tree.getFirst().org().name()).isEqualTo("总部");
    // ★ 用 containsAll 而不是 containsExactly：本类共用一个容器，别的用例会在根下建探针节点 ——
    //   而"根下恰好只有八个"不是这条判据要守的东西（要守的是"种子那八个都在"）。
    // ★ 顺序也不断言成迁移里的书写顺序：tree() 按 path 排（即按 code），那是有意的（可预测），
    //   所以这里断言"有序"而不是"按我写的顺序"。
    assertThat(tree.getFirst().children())
        .extracting(node -> node.org().name())
        .containsAll(List.of("采购部", "财务部", "法务部", "人事部", "技术部", "行政部", "市场部", "生产部"));
    assertThat(tree.getFirst().children().stream().map(node -> node.org().code()).toList())
        .as("按 path（= code）升序 —— 前端不必再排")
        .isSorted();
  }

  @Test
  void creatingChildBuildsThePath() {
    Org parent = repository.findByCode("PURCHASING").orElseThrow();

    Org child = orgs.create(parent.id(), "SUB_PROBE", "采购部-验货组", 1, ACTOR);

    assertThat(child.path())
        .as("子路径 = 父路径 + 自己的 code + 结尾斜杠")
        .isEqualTo(parent.path() + "SUB_PROBE/");
    assertThat(repository.findSubtree(parent.path())).extracting(Org::code).contains("SUB_PROBE");
  }

  @Test
  void movingRewritesTheWholeSubtree() {
    Org purchasing = repository.findByCode("PURCHASING").orElseThrow();
    Org finance = repository.findByCode("FINANCE").orElseThrow();
    Org group = orgs.create(purchasing.id(), "MOVE_PROBE", "搬家组", 1, ACTOR);
    Org leaf = orgs.create(group.id(), "MOVE_LEAF", "搬家组-下级", 1, ACTOR);

    orgs.move(group.id(), finance.id(), ACTOR);

    assertThat(repository.findById(group.id()).orElseThrow().path())
        .as("自己走了")
        .isEqualTo(finance.path() + "MOVE_PROBE/");
    assertThat(repository.findById(leaf.id()).orElseThrow().path())
        .as("★ 下级跟着走（一次重写完整棵子树 —— 逐个改会留下'父已改、子未改'的窗口）")
        .isEqualTo(finance.path() + "MOVE_PROBE/MOVE_LEAF/");
  }

  @Test
  void movingIntoOwnSubtreeIsRejected() {
    Org parent = repository.findByCode("TECH").orElseThrow();
    Org child = orgs.create(parent.id(), "CYCLE_PROBE", "环探测", 1, ACTOR);

    assertThatThrownBy(() -> orgs.move(parent.id(), child.id(), ACTOR))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("自己的下级");
  }

  /**
   * ★★ 金丝雀：物化路径的前缀匹配**不许跨部门**。
   *
   * <p>建一个 code 为 {@code AD} 的部门（路径 {@code /ROOT/AD/}），它**不是** {@code /ROOT/ADMIN/} 的祖先。
   * 若前缀少了结尾斜杠（或在比较时忘了它），{@code AD} 的子树会包含 {@code ADMIN} 里的东西 —— 症状是"一个部门看到了另一个部门的资料"，而**查询不报错**。
   */
  @Test
  void subtreePrefixNeverCrossesDepartments() {
    Org root = repository.findByCode("ROOT").orElseThrow();
    Org ad = orgs.create(root.id(), "AD", "广告部", 9, ACTOR);

    List<String> subtree = repository.findSubtree(ad.path()).stream().map(Org::code).toList();

    assertThat(subtree).as("子树只含自己").containsExactly("AD");
    assertThat(subtree)
        .as("★ 绝不能因为 `/ROOT/AD/` 是 `/ROOT/ADMIN/` 的前缀片段就把 ADMIN 拉进来")
        .doesNotContain("ADMIN");
  }

  @Test
  void deleteRefusesNonEmptyDepartmentsAndPointsToDisable() {
    Org parent = repository.findByCode("LEGAL").orElseThrow();
    orgs.create(parent.id(), "DEL_PROBE", "待删组", 1, ACTOR);

    assertThatThrownBy(() -> orgs.delete(parent.id()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).hint())
        .asString()
        .contains("停用");

    // 空部门可以删
    Org empty = orgs.create(parent.id(), "DEL_EMPTY", "空组", 2, ACTOR);
    orgs.delete(empty.id());
    assertThat(repository.findById(empty.id())).isEmpty();
  }

  @Test
  void deleteRefusesDepartmentsWithUsers() {
    // ★ 挂在 ROOT 下而不是建成新的根：本类共用一个容器，探针如果建成根，
    //   第一个用例的"只有一个根"就会被后面的用例弄红（夹具互相污染 —— 本项目在闸门里也踩过）
    Org root = repository.findByCode("ROOT").orElseThrow();
    Org target = orgs.create(root.id(), "EMPTY_PROBE", "有人的组", 9, ACTOR);
    UUID user = jdbc.queryForObject("select id from iam_user limit 1", UUID.class);
    // ★ 记下原值、结束还原**原值**（不是还原成 null）：V28 起演示账号本身有部门归属，
    //   把它的归属清成 null 会污染共用容器里后续用例看到的世界（夹具互相污染本项目踩过多次）。
    UUID previousOrg =
        jdbc.queryForObject("select org_id from iam_user where id = ?", UUID.class, user);
    jdbc.update("update iam_user set org_id = ? where id = ?", target.id(), user);

    try {
      assertThatThrownBy(() -> orgs.delete(target.id()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("用户");
    } finally {
      jdbc.update("update iam_user set org_id = ? where id = ?", previousOrg, user);
    }
  }

  @Test
  void duplicateCodeIsRejected() {
    assertThatThrownBy(() -> orgs.create(null, "FINANCE", "财务一部", 9, ACTOR))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已存在");
  }

  @Test
  void duplicateSiblingNameIsRejectedButCrossParentNameIsAllowed() {
    Org root = repository.findByCode("ROOT").orElseThrow();
    // ★ 用户 2026-10-08 指出的那条：展示名重名 = 让"按名字比较"的地方把两个部门混成一个
    //   （一个部门看到另一个部门的文档，且不报错）。同级必须挡。
    assertThatThrownBy(() -> orgs.create(root.id(), "FIN999", "财务部", 99, ACTOR))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("同级已有叫");

    // ★ 但跨父重名是**允许**的：两个分公司各自有个「行政部」是合理的组织形态。
    Org branch = orgs.create(root.id(), "BRANCH_SOUTH", "华南分公司", 20, ACTOR);
    Org southAdmin = orgs.create(branch.id(), "ADMIN_S", "行政部", 1, ACTOR);
    assertThat(southAdmin.path()).isEqualTo("/ROOT/BRANCH_SOUTH/ADMIN_S/");
    // ★ 两条都叫「行政部」，但身份不同（不同 path）—— 这正是"展示名不是身份"的证明：
    //   凡按名字比较的地方都会把这两条混成一个，所以判定必须走 code / path。
    assertThat(southAdmin.name()).isEqualTo("行政部");
    assertThat(southAdmin.path()).isNotEqualTo(repository.findByCode("ADMIN").orElseThrow().path());
    branchRepositoryCleanup(branch.id());
  }

  /** 用例收尾：本类的容器/库被多个用例共用，建出来的分支要拆掉，免得影响别的用例对"树里有什么"的断言。 */
  private void branchRepositoryCleanup(UUID branchId) {
    orgs.delete(repository.findByCode("ADMIN_S").orElseThrow().id());
    orgs.delete(branchId);
  }

  @Test
  void disablingKeepsTheNodeInTheTree() {
    // ★ 同样挂在 ROOT 下（理由见上一个用例的注释）
    Org root = repository.findByCode("ROOT").orElseThrow();
    Org probe = orgs.create(root.id(), "DISABLE_PROBE", "将停用的组", 9, ACTOR);

    orgs.update(probe.id(), null, null, false, ACTOR);

    assertThat(repository.findById(probe.id()).orElseThrow().enabled())
        .as("★ 停用 ≠ 撤销：节点还在，既有共享仍然有效（07 §10.5 判据 3）")
        .isFalse();
    assertThat(orgs.tree()).as("树里仍然看得到它（维护页要能看到自己停用过什么）").isNotEmpty();
  }
}
