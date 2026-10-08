package cn.lincoln.aiplatform.identity.application;

import cn.lincoln.aiplatform.identity.domain.Org;
import cn.lincoln.aiplatform.identity.domain.OrgRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织机构（{@code 07 §十}）—— 树形维护 + 移动/停用的守卫。
 *
 * <h3>★ 三条守卫，都是"不做就会静默出错"的那类</h3>
 *
 * <ol>
 *   <li><strong>{@code code} 唯一且不可改</strong>：它是共享主体（grant 的 {@code subject_id}）与审计里的标识。
 *       改它等于把"共享给谁"悄悄指到别处。
 *   <li><strong>不许把部门移进自己的子树</strong>：那会形成一个父子环 —— 树遍历与"含下级"的前缀匹配
 *       会立刻失去意义，而<strong>不报错</strong>（查询照样跑，只是结果错）。
 *   <li><strong>有下级或有用户的部门不许删</strong>：删了它们会变成孤儿（用户 {@code org_id} 指向不存在的行）。 要"下线"请用**停用**（停用 ≠
 *       撤销：既有共享仍然有效，{@code 07 §10.5} 判据 3）。
 * </ol>
 *
 * <p>★ 改名要谨慎：{@code name} 会进文档的**召回前缀**与 {@code content_hash}（{@code 12 §4.2}）⇒ 改名意味着该部门下的文档"前缀变了 ⇒
 * 需要重新向量化"。所以这里**不阻止**改名，但把它当成 一次显式操作交给调用方去提示（服务层擅自重算全部文档会更糟：那是一次看不见的大批量写入）。
 */
@Service
public class OrgService {

  private final OrgRepository orgs;

  public OrgService(OrgRepository orgs) {
    this.orgs = orgs;
  }

  /** 树（根节点的子树，嵌套形态）。★ 返回的是**扁平+嵌套**两层：前端树控件要嵌套，审计/日志要扁平。 */
  @Transactional(readOnly = true)
  public List<OrgNode> tree() {
    List<Org> all = orgs.findAllOrdered();
    Map<UUID, List<OrgNode>> childrenByParent = new LinkedHashMap<>();
    Map<UUID, OrgNode> nodes = new LinkedHashMap<>();
    for (Org org : all) {
      nodes.put(org.id(), new OrgNode(org, new ArrayList<>()));
    }
    for (Org org : all) {
      OrgNode node = nodes.get(org.id());
      if (org.parentId() == null || !nodes.containsKey(org.parentId())) {
        continue; // 根节点在下面按"没有父"收集
      }
      childrenByParent.computeIfAbsent(org.parentId(), key -> new ArrayList<>()).add(node);
    }
    nodes
        .values()
        .forEach(
            node -> node.children().addAll(childrenByParent.getOrDefault(nodeId(node), List.of())));

    List<OrgNode> roots = new ArrayList<>();
    for (Org org : all) {
      if (org.parentId() == null) {
        roots.add(nodes.get(org.id()));
      }
    }
    return roots;
  }

  /** 扁平清单（列表页、下拉、共享主体选择器用；★ 树控件用 {@link #tree()}）。 */
  @Transactional(readOnly = true)
  public List<Org> list() {
    return orgs.findAllOrdered();
  }

  @Transactional
  public Org create(UUID parentId, String code, String name, Integer sort, UUID actorId) {
    String trimmedCode = requireCode(code);
    if (orgs.existsByCode(trimmedCode)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "部门编码「" + trimmedCode + "」已存在",
          "编码是共享主体与审计里的标识，必须唯一且稳定 —— 要改展示名请编辑那一项");
    }
    // ★★ 兄弟之间展示名唯一（用户 2026-10-08 指出重名会造成部门间"看到别人文档"的静默越权）：
    //   展示名会出现在召回前缀（今天）与人读的位置（界面/引用/提示词渲染），重名会让**按名字比较**
    //   的地方把两个部门混成一个。跨父重名允许 —— 两个分公司各自有个「行政部」是合理的。
    String trimmedName = name == null ? "" : name.trim();
    if (!trimmedName.isEmpty() && orgs.existsSiblingName(parentId, trimmedName)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "同级已有叫「" + trimmedName + "」的部门",
          "展示名在**同级**必须唯一（重名会让按名字比较的地方把两个部门混成一个，" + "表现为一个部门看到另一个部门的文档且不报错）；跨级重名是允许的");
    }
    String parentPath = "/";
    if (parentId != null) {
      Org parent =
          orgs.findById(parentId)
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "上级部门不存在", "请刷新后重试"));
      parentPath = parent.path();
    }
    if (!parentPath.endsWith("/")) {
      // 前缀必须自带结尾斜杠，否则 /ROOT/AD 会 match /ROOT/ADMIN/（一次"看到别人部门"的静默越权）
      parentPath = parentPath + "/";
    }
    Org created =
        new Org(
            UUID.randomUUID(),
            parentId,
            parentPath,
            trimmedCode,
            name,
            sort == null ? 0 : sort,
            actorId);
    return orgs.save(created);
  }

  @Transactional
  public Org update(UUID id, String name, Integer sort, Boolean enabled, UUID actorId) {
    Org org = require(id);
    if (name != null) {
      org.rename(name, actorId);
    }
    if (sort != null) {
      org.setSort(sort, actorId);
    }
    if (enabled != null) {
      org.setEnabled(enabled, actorId);
    }
    return orgs.save(org);
  }

  /**
   * 搬到一个新的上级之下（{@code newParentId == null} = 变成根）。
   *
   * <p>★ 子树路径**一次重写**：逐个改会在中途留下"父已改、子未改"，而那个窗口里的数据权限判定是错的。
   */
  @Transactional
  public Org move(UUID id, UUID newParentId, UUID actorId) {
    Org org = require(id);
    String newParentPath = "/";
    if (newParentId != null) {
      if (newParentId.equals(id)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "不能把部门移到它自己下面");
      }
      Org parent = require(newParentId);
      if (parent.path().startsWith(org.path())) {
        // ★ 移进自己的子树会形成父子环：树遍历与"含下级"的前缀匹配会立刻失去意义，而且**不报错**
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "不能把「" + org.name() + "」移到它自己的下级「" + parent.name() + "」下面",
            "那会形成父子环：树与'含下级'的判定都会失去意义，而检索不会报错");
      }
      newParentPath = parent.path();
    }
    if (!newParentPath.endsWith("/")) {
      newParentPath = newParentPath + "/";
    }
    String oldPrefix = org.path();
    String newPrefix = newParentPath + org.code() + "/";
    org.moveTo(newParentId, newPrefix, actorId);
    orgs.save(org);
    // 子树跟着走（含自己已在上面改过，这里改的是它的后代）
    orgs.rewriteSubtreePath(oldPrefix, newPrefix);
    return org;
  }

  /** 删一个**空**部门（没有下级、没有用户）。有内容时明确拒绝并指路到"停用"。 */
  @Transactional
  public void delete(UUID id) {
    Org org = require(id);
    List<Org> subtree = orgs.findSubtree(org.path());
    if (subtree.size() > 1) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "「" + org.name() + "」还有 " + (subtree.size() - 1) + " 个下级部门，不能删除",
          "请先处理下级部门；如果只是要停止使用，请**停用**它（停用不会影响既有共享）");
    }
    long users = orgs.countUsers(id);
    if (users > 0) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "「" + org.name() + "」下还有 " + users + " 个用户，不能删除",
          "请先把这些用户改到别的部门；或**停用**这个部门（停用不会影响既有共享）");
    }
    orgs.delete(org);
  }

  // ────────────────────────── 内部 ──────────────────────────

  private Org require(UUID id) {
    return orgs.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "部门不存在", "请刷新后重试"));
  }

  private static String requireCode(String code) {
    if (code == null || code.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少部门编码", "编码是稳定标识，不能为空");
    }
    String trimmed = code.trim();
    if (!trimmed.matches("[A-Za-z0-9_]{1,64}")) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "部门编码只允许字母、数字与下划线，且不超过 64 字：收到「" + trimmed + "」");
    }
    return trimmed;
  }

  private static UUID nodeId(OrgNode node) {
    return node.org().id();
  }

  /**
   * 树节点（返回给界面的是嵌套形态）。
   *
   * @param org 这一层的实体（界面用它取 code/name/sort/enabled）
   * @param children 下级（★ 可变列表：构建时填充，避免递归构造的复杂度）
   */
  public record OrgNode(Org org, List<OrgNode> children) {}
}
