package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.platform.org.OrgDirectoryPort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把文档的责任部门翻译成<strong>进前缀与 {@code content_hash} 的那一段材料</strong>（{@code 07 §11.2}）。
 *
 * <h3>★ 为什么是 {@code code} 而不是 {@code name}</h3>
 *
 * <p>{@code SearchText.prefix/contentHash} 的第二个参数自 {@code T300} 起是部门的稳定 {@code code}：
 *
 * <ul>
 *   <li>{@code name} 可改 ⇒ 一次纯展示性改名会让该部门下<strong>所有</strong>文档的前缀与哈希失效； 而且可变、可重名 ⇒
 *       按名字比较的地方会把两个部门混成一个（静默越权，issue #37）；
 *   <li>{@code path} 移动会变 ⇒ 整棵子树下的文档一起连坐；
 *   <li>{@code code} 全局唯一且不可改 ⇒ 改名、移动、重排都与它无关（{@code 07 §11.4} 判据 1/2）。
 * </ul>
 *
 * <h3>★ 为什么单独一个类</h3>
 *
 * <p>两处调用方（{@code DocumentService} 与 {@code SegmentService}，共 5 个调用点）都必须做同一件事：
 * <strong>每份文档查一次、拿不到时给同一个占位</strong>。各写一遍的话，"取不到时怎么办"会分叉 —— 而分叉的症状是同一份文档在两条路径下写出<strong>不同的 {@code
 * content_hash}</strong>（检索会漏，界面看不出来）。
 *
 * <h3>★ 存量数据的占位：{@code no-org}</h3>
 *
 * <p>{@code org_id} 为空的文档（V27 回填时匹配不上的那些）<strong>不许报错崩掉</strong> ——
 * 它们的正文仍然要能切段、能被检索（只是前缀里的部门段退化成占位）。取 {@code no-org} 的理由：
 *
 * <ol>
 *   <li><strong>不会与真实 code 撞</strong>：{@code OrgService} 只接受 {@code [A-Za-z0-9_]}，横杠进不去；
 *   <li><strong>非空</strong>：前缀三项非空是 {@link cn.lincoln.aiplatform.knowledge.domain.Segmenter}
 *       的硬校验，用空串会让整篇文档直接分段失败；
 *   <li><strong>可读</strong>：它会出现在 {@code search_text} 里 —— 运维一眼能看出"这篇没有责任部门" （静默塞一个空串或"未知"就查不出来了）。
 * </ol>
 *
 * <p>★ 但<strong>新建档绝不允许为空</strong>：那是应用层的必填校验（{@code Document} 构造器 + {@code
 * DocumentService.register} 的存在性校验），不靠这里兜。
 */
@Component
public class OrgCodeResolver {

  private static final Logger log = LoggerFactory.getLogger(OrgCodeResolver.class);

  /** {@code org_id} 为空（存量未匹配）时进前缀的占位码。见类注释的三条理由。 */
  public static final String NO_ORG_CODE = "no-org";

  private final OrgDirectoryPort orgs;

  public OrgCodeResolver(OrgDirectoryPort orgs) {
    this.orgs = orgs;
  }

  /** 该文档的责任部门 code（{@code org_id} 为空 ⇒ {@link #NO_ORG_CODE}）。 */
  public String codeOf(Document document) {
    return codeOf(document.orgId());
  }

  /**
   * 按 {@code org_id} 取 code。
   *
   * <p>★ 取不到时<strong>不抛异常</strong>而是给占位 + WARN：这条路径在段编辑/迁移/重新切段里都会走，
   * 抛异常等于让一份存量文档<strong>再也编辑不了</strong>。而"指向已不存在的部门"理论上被外键挡住 —— 真出现只可能是数据被外力改过，那种事必须留痕（不许静默）。
   */
  public String codeOf(UUID orgId) {
    if (orgId == null) {
      return NO_ORG_CODE;
    }
    return orgs.findById(orgId)
        .map(OrgDirectoryPort.OrgBrief::code)
        .orElseGet(
            () -> {
              log.warn("文档的 org_id={} 在组织树里查不到 ⇒ 前缀用占位码 {}（请核对数据）", orgId, NO_ORG_CODE);
              return NO_ORG_CODE;
            });
  }
}
