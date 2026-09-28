package cn.lincoln.aiplatform.modelcatalog.application;

/**
 * 能力来源（契约 {@code ModelCapabilitySource}）。
 *
 * <p>★ 界面必须区分展示：{@code PROBED} 是从上游探测到的（可信）， {@code MANUAL}
 * 是用户手工声明的（可能是猜测）。两者混在一起展示会让用户误以为"手工填的"也被验证过。
 */
public enum ModelCapabilitySource {
  PROBED,
  MANUAL
}
