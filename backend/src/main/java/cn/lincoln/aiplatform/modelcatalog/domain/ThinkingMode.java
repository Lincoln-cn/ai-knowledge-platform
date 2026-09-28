package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 思考模式能力三态（契约 {@code ThinkingMode}）。
 *
 * <p>★ 取值与契约一字不差（{@code NONE | TOGGLEABLE | ALWAYS_ON}），{@code @Enumerated(STRING)} 直接落字面量。
 *
 * <p>三态决定界面开关如何呈现：{@code TOGGLEABLE} 才显示「思考」开关， {@code ALWAYS_ON} 只读展示，{@code NONE} 不出现。
 * 它是<strong>能力声明</strong>而不是配置项 —— 声明错了不会让服务崩，但会让用户对着一个永远没反应的开关。
 */
public enum ThinkingMode {
  NONE,
  TOGGLEABLE,
  ALWAYS_ON
}
