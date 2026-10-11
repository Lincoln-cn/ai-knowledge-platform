package cn.lincoln.aiplatform.knowledge.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 结构化前缀与 {@code search_text}（第 7 周 · 0.2.2 · {@code 12 §4.2}）。
 *
 * <p>★ 为什么值得做（{@code 12 §4.1}，有实证）：结构化前缀是<strong>零 LLM</strong> 的确定性前缀 —— Meta-Prefix 实验失败率 30% →
 * 3.3% （Context@5 从 26.7 → 61.7）。 先做零成本的这一档；逐段 LLM 摘要反而会把 B6（≥3 段/秒）打穿（{@code 12 §6.3}）。
 *
 * <p>前缀形态（{@code 12 §4.2}）：<strong>文种·部门·标题｜章节路径</strong>， 其中「部门」自 {@code T300} 起是该部门的稳定 {@code
 * code}（不是展示名，见 {@link #prefix}）。
 *
 * <p>三条纪律：
 *
 * <ol>
 *   <li>前缀绝不进 {@code content}（引用展示原文，不许带前缀）
 *   <li>前缀里的标签也是可过滤的列（前缀给向量/全文，过滤走 SQL WHERE）
 *   <li>改了前缀组成 ⇒ {@code contentHash} 必须变（{@code SEG-I7}）
 * </ol>
 */
public final class SearchText {

  /** 前缀与正文的分隔符。用换行：前缀是元信息，正文从新行开始，避免拼成一个怪词。 */
  public static final String SEPARATOR = "\n";

  /** 前缀长度上限（{@code 12 §10.2} 风险 1：前缀挤占向量预算）。 设上限而不是拒绝 —— 标题再长也不该让整篇入库失败。 */
  public static final int MAX_PREFIX_LENGTH = 80;

  private SearchText() {}

  /**
   * 拼结构化前缀：<strong>文种·部门·标题｜章节路径</strong>。
   *
   * <p>★ 第二个参数是部门的<strong>稳定标识 {@code code}</strong> —— 这处收口是 {@code 07 §11.2} / issue #37
   * 的核心：<strong>不许放 {@code name}</strong>（可改：一次改名就会让该部门下所有文档的 前缀与哈希失效，而那是纯展示性改动，界面上什么都看不出来）、
   * <strong>不许放 {@code path}</strong> （移动部门会变：整棵子树下的文档一起连坐）、也<strong>不许放自由字符串</strong>（可重名 ⇒ 按名字比较的
   * 地方会把两个部门混成一个 ⇒ 静默越权）。只放 {@code code}：全局唯一且不可改，改名/移动/重名都与它无关。
   *
   * <p>人读的部门名不进这里：它在<strong>生成链路渲染时</strong>由 {@code org_id} 查出来（与密级/文种 走字典渲染是同一套模式）。★ 这条同时是判据的哨兵
   * —— （{@code 07 §11.4} 判据 1/2）改名、移动、 改排序之后，本方法对同一份文档必须输出<strong>逐字节相同</strong>的前缀。
   *
   * <p>超长时截到 {@link #MAX_PREFIX_LENGTH}（保留开头的 L1 文档级标识 —— 贡献最大）。
   */
  public static String prefix(String docType, String orgCode, String title, String titlePath) {
    String raw = docType + "·" + orgCode + "·" + title + "｜" + titlePath;
    if (raw.length() <= MAX_PREFIX_LENGTH) {
      return raw;
    }
    return raw.substring(0, MAX_PREFIX_LENGTH);
  }

  /** {@code search_text = 前缀 + 正文}（{@code 12 §4.2}）。 */
  public static String of(String prefix, String body) {
    return prefix + SEPARATOR + body;
  }

  /** 从 {@code searchText} 无损还原正文（前缀已知时）。 */
  public static String contentOf(String searchText, String prefix) {
    String head = prefix + SEPARATOR;
    if (!searchText.startsWith(head)) {
      throw new IllegalArgumentException("searchText 不含给定前缀，无法还原正文");
    }
    return searchText.substring(head.length());
  }

  /**
   * {@code contentHash}（{@code SEG-I7}）：必须包含前缀的<strong>完整</strong>组成。
   *
   * <p>★ 用<strong>未截断</strong>的组成做哈希输入 —— 否则改了被前缀截断吃掉的那半截标题，hash 不变，违反 {@code SEG-I7}。
   *
   * <p>★ 第二个参数同 {@link #prefix}：放部门的稳定 {@code code}，不放 {@code name} / {@code path}。
   * 否则一次改名或一次移动就会让成百上千条存量向量被判定为"需要重算"，而那是**纯展示性改动**。
   *
   * <p>hash 形态：sha256(文种|部门code|标题|章节路径|正文) 的十六进制（64 字符），与 {@code 02} 的 {@code content_hash
   * varchar(64)} 对齐。
   */
  public static String contentHash(
      String docType, String orgCode, String title, String titlePath, String body) {
    String material = docType + "|" + orgCode + "|" + title + "|" + titlePath + "|" + body;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 不支持 SHA-256", e);
    }
  }
}
