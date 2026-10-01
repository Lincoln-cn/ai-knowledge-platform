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
 * <p>前缀形态（{@code 12 §4.2}）：<strong>文种·部门·标题｜章节路径</strong>。
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
   * <p>超长时截到 {@link #MAX_PREFIX_LENGTH}（保留开头的 L1 文档级标识 —— 贡献最大）。
   */
  public static String prefix(String docType, String department, String title, String titlePath) {
    String raw = docType + "·" + department + "·" + title + "｜" + titlePath;
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
   * <p>hash 形态：sha256(文种|部门|标题|章节路径|正文) 的十六进制（64 字符），与 {@code 02} 的 {@code content_hash
   * varchar(64)} 对齐。
   */
  public static String contentHash(
      String docType, String department, String title, String titlePath, String body) {
    String material = docType + "|" + department + "|" + title + "|" + titlePath + "|" + body;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 不支持 SHA-256", e);
    }
  }
}
