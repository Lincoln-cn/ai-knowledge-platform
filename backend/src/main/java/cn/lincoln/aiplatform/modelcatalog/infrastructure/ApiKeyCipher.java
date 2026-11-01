package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.domain.CryptoPort;
import cn.lincoln.aiplatform.platform.infrastructure.AesGcmCryptoPort;

/**
 * 模型 API key 的加解密 —— <strong>本类只是端口的适配壳</strong>（{@code #74}）。
 *
 * <p>★ <b>为什么必须加密落库</b>：契约 {@code UpsertModelBody.apiKey} 是明文收进来的， 但等保项目<strong>明文入库是硬伤</strong> ——
 * 库被读走 ≠ 密钥被拿走（与刷新令牌只存 sha256 同一条立场， 见 {@code V3} 迁移注释）。
 *
 * <p>★ <b>算法去哪了</b>：AES-GCM 与密钥校验都搬到了 {@link AesGcmCryptoPort}（{@link CryptoPort} 的实现）。本类保留 {@link
 * #encrypt}/{@link #decrypt} 与 {@link Sealed} 两个形状，是因为 <strong>34 处测试与调用点都按这个形状写</strong> ——
 * 等价重构的判据就是"现有测试一条不改、全部照旧通过"。★ 真上国密时改的是 {@code platform.infrastructure} 里的实现与 {@code CryptoConfig}
 * 的 bean，<strong>不是这里</strong>。
 *
 * <p>★ <b>纪律</b>：任何视图都不得回传明文（下一段的 DTO 里不放 {@code apiKey} 字段， 与 {@code UserView} 不含 {@code
 * passwordHash} 同理）。这里的方法只在「写入前加密」「调用供应商前解密」两个场景被调用， 解密结果不进日志、不进响应。
 */
public class ApiKeyCipher {

  private final CryptoPort cryptoPort;

  /**
   * 按密钥文本构造（测试与「手上有配置文本」的场景用）。
   *
   * @param secretBase64 Base64 编码的 32 字节密钥（配置 {@code app.model.api-key-secret}）。校验在 {@link
   *     AesGcmCryptoPort} 的构造里做 —— 配错密钥应该在<strong>启动</strong>看到可读异常。
   */
  public ApiKeyCipher(String secretBase64) {
    this(new AesGcmCryptoPort(secretBase64));
  }

  /**
   * 按端口装配 —— 生产路径：bean 由 {@link AiModelConfig} 用 {@code CryptoPort} 装配，算法实现只在平台层落地一次。
   *
   * <p>★ 用静态工厂而不是第二个构造器：{@code new ApiKeyCipher(null)} 会同时匹配 {@code String} 与 {@code CryptoPort}
   * 两个重载（既有测试里就有这一句），改成工厂后调用点保持不歧义 —— 而"传 null 应报可读错误"那条断言才仍然测的是它想测的东西。
   */
  static ApiKeyCipher of(CryptoPort cryptoPort) {
    return new ApiKeyCipher(cryptoPort);
  }

  private ApiKeyCipher(CryptoPort cryptoPort) {
    this.cryptoPort = cryptoPort;
  }

  /** 一个已加密的 API key：密文与 IV 都是 base64 文本，可直接落库。 */
  public record Sealed(String cipherBase64, String ivBase64) {}

  /**
   * 加密。每次调用生成随机 IV —— 同一明文两次加密得到不同密文。
   *
   * @return 密文与 IV（均为 base64）。
   */
  public Sealed encrypt(String plaintext) {
    CryptoPort.Sealed sealed = cryptoPort.seal(plaintext);
    return new Sealed(sealed.cipherBase64(), sealed.ivBase64());
  }

  /**
   * 解密。
   *
   * @param cipherBase64 {@link #encrypt} 产出的密文（base64）。
   * @param ivBase64 {@link #encrypt} 产出的 IV（base64）。
   * @return 明文。★ 调用方拿到后只用于调供应商，不进日志、不进响应。
   * @throws IllegalArgumentException 密钥不对、密文被篡改、或 base64 不合法 —— 可读异常。
   */
  public String decrypt(String cipherBase64, String ivBase64) {
    return cryptoPort.open(cipherBase64, ivBase64);
  }
}
