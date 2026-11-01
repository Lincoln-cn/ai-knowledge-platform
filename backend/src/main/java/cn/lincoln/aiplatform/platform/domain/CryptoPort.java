package cn.lincoln.aiplatform.platform.domain;

/**
 * 密码学能力端口（{@code #74} 的「留缝」，范式取自 {@code 09 §3.13}：换供应商只改一处）。
 *
 * <h3>为什么要有这一层</h3>
 *
 * <p>国密（SM2/SM3/SM4）与密评在客户是政务、国企、金融时不是优化项而是<strong>前置门槛</strong>。改造前的事实是：AES-GCM 的 {@code Cipher}
 * 直接写在 {@code modelcatalog} 的 {@code ApiKeyCipher} 里、SHA-256 直接写在 {@code AuthService} 里 ⇒
 * <strong>真上国密时改的是若干处调用点，而不是一个实现</strong>，而且漏掉一处不会有任何测试变红（{@code #74} 的根因）。
 *
 * <p>现在这两类调用都走这里：将来要国密，只需<strong>新增一个实现并换 {@code @Bean}</strong>，调用点一行不改。
 *
 * <h3>★ 边界：这个端口只装「真会被消费的东西」</h3>
 *
 * <ul>
 *   <li><strong>摘要在里面</strong>：刷新令牌的哈希（{@code AuthService}）就是安全用途，换 SM3 要落到这里。
 *   <li><strong>对称加解密在里面</strong>：模型 API key 的落库加密（{@code ApiKeyCipher}）同理。
 *   <li>★ <strong>签名不在里面</strong>：JWT 的签发与验签走官方的 {@code spring-security-oauth2-jose}（{@code #62}
 *       之前的 口径），换国密是"jose 能不能挂 SM2 的 {@code JWSAlgorithm}"的问题。在这里开一个永远不会被消费的 {@code sign}/{@code
 *       verify}，等于留一条没人走的缝 —— 本项目已经有"留了数据层却被读成已接入"的前车之鉴（{@code #78} 判据 ③）。
 * </ul>
 *
 * <p>★ <strong>不要现在实现 SM 系列</strong>：没有客户需求时写它是"写一份无人使用的代码"。这一层的价值是<strong>把替换点收成一个</strong>，
 * 不是提前把算法写出来。
 */
public interface CryptoPort {

  /**
   * 一段已加密的数据：密文与 IV 都是 Base64 文本，可直接落两列。
   *
   * <p>IV 不是秘密，与密文分两列存即可。
   */
  record Sealed(String cipherBase64, String ivBase64) {}

  /**
   * 摘要（小写十六进制）。
   *
   * <p>用途是「不可逆、只用来比对」—— 例如刷新令牌只存哈希、按哈希查行（见 {@code AuthService} 的注释：这是有意用 sha256 而不是 bcrypt，因为令牌是
   * 256 位随机值，没有可猜的密码空间）。
   */
  String digestHex(String plain);

  /**
   * 对称加密。
   *
   * @return 密文与 IV（均为 Base64）。★ 同一明文两次加密必须得到不同密文（IV 随机）—— 否则库被读走时一眼就能看出"这几个模型用的是同一个 key"。
   * @throws IllegalArgumentException 明文为 {@code null}
   */
  Sealed seal(String plaintext);

  /**
   * 对称解密。
   *
   * @return 明文。★ 调用方拿到后只用于调供应商，不进日志、不进响应。
   * @throws IllegalArgumentException 密钥不对、密文被篡改、或 Base64 不合法 —— 三种都抛可读异常，因为使用者的下一步动作完全一样
   */
  String open(String cipherBase64, String ivBase64);
}
