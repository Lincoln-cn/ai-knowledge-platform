package cn.lincoln.aiplatform.platform.access;

/**
 * 资源的**可见性**（{@code 07 §3.1}）—— 回答"这条记录对谁可见"。
 *
 * <p>★ <strong>{@link #public} 不等于免登录</strong>：它是"<em>登录用户里</em>人人可见"。 免登录只有公开凭证（{@code
 * AccessCredential} / 分享链接）那一条链路。 两者混淆过一次就够造成越权了，所以这句话同时写在文档、契约与本枚举上。
 *
 * <p>★ 枚举名用大写下划线（{@code private} 是 Java 关键字，不能直接做标识符）， 而库里与契约里存的是小写 —— 由 {@code
 * VisibilityConverter} 负责这层映射。 映射只放在一处，避免"有的地方存小写、有的地方存大写"。
 */
public enum Visibility {
  /** 仅自己（默认值，见 {@code 08 §4.4.4}：新建资源一律收窄 —— 权限放开容易，收回难） */
  PRIVATE,
  /** 指定主体可见（{@code grants} 才有意义） */
  SHARED,
  /** 登录用户人人可见 */
  PUBLIC
}
