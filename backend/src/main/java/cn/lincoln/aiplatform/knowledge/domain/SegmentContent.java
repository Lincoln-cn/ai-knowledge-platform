package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;

/**
 * 分段内容值对象（{@code SEG-I1} / {@code SEG-I5}）—— 构造即校验。
 *
 * <p>★ 原项目的真实事故（{@code 02 §2.3}）：向量模型上下文约 1K token，超出部分被<strong>静默截断</strong>。 界面允许切到 4096 字符 ⇒
 * 运营在完全不知情的情况下切出大量"只被向量看到前半段"的分段。 本方案把约束提到值对象上：<strong>构造即校验、拒绝时给建议</strong> ——
 * 同一个物理限制，原来是隐性损失，现在是显性规则。
 *
 * <p>★ 上限<strong>不是硬编码常量</strong>，而是随向量模型变化的领域配置（{@code 02 §2.3}）。 调用方从绑定的向量模型能力换算出 {@code
 * maxChars} 传进来。
 *
 * <p>★ {@code SEG-I5}：进向量的是 <strong>{@code searchText}（含前缀）</strong>而不是 {@code content} ⇒ 前缀挤占预算，
 * {@code segmentLength} 的默认值要扣掉前缀长度（{@code 12 §9.1}）。
 */
public final class SegmentContent {

  private final String text;

  private SegmentContent(String text) {
    this.text = text;
  }

  /**
   * 按 content 构造（{@code SEG-I1}）。
   *
   * @param text 正文（不许为空）
   * @param maxChars 字符数上限（向量模型能力换算得出）
   * @throws BusinessException 超长（{@link ErrorCode#SEGMENT_TOO_LONG}）时带可读原因与 hint
   */
  public static SegmentContent of(String text, int maxChars) {
    validate(text, maxChars);
    return new SegmentContent(text);
  }

  /**
   * 按 {@code searchText = 前缀 + 正文} 构造（{@code SEG-I5}）—— 前缀挤占向量预算。
   *
   * @param prefix 结构化前缀（{@code 12 §4.2}）
   * @param body 正文
   * @param maxChars 向量模型字符上限
   */
  public static SegmentContent ofSearchText(String prefix, String body, int maxChars) {
    if (prefix == null || prefix.isEmpty()) {
      throw new IllegalArgumentException("前缀不能为空 —— searchText 必须含结构化前缀");
    }
    if (body == null) {
      throw new IllegalArgumentException("正文不能为空");
    }
    String searchText = prefix + SearchText.SEPARATOR + body;
    validate(searchText, maxChars);
    return new SegmentContent(searchText);
  }

  private static void validate(String text, int maxChars) {
    if (text == null || text.isEmpty()) {
      throw new IllegalArgumentException("分段内容不能为空");
    }
    if (maxChars <= 0) {
      throw new IllegalArgumentException("上限必须为正数");
    }
    int actual = text.length();
    if (actual > maxChars) {
      int suggestedParts = (actual + maxChars - 1) / maxChars;
      throw new BusinessException(
          ErrorCode.SEGMENT_TOO_LONG,
          "该内容 " + actual + " 字，超出当前向量模型上限 " + maxChars + " 字。",
          "建议按段落切分为 " + suggestedParts + " 段，或切换至长上下文向量模型。");
    }
  }

  public String text() {
    return text;
  }

  public int length() {
    return text.length();
  }
}
