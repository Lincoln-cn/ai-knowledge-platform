package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 模型 API key 的加解密（AES-GCM）。
 *
 * <p>★ <b>为什么必须加密落库</b>：契约 {@code UpsertModelBody.apiKey} 是明文收进来的， 但等保项目<strong>明文入库是硬伤</strong> ——
 * 库被读走 ≠ 密钥被拿走（与刷新令牌只存 sha256 同一条立场， 见 {@code V3} 迁移注释）。
 *
 * <p>★ <b>为什么是 JDK 自带的 AES-GCM</b>：不引第三方加密库。GCM 是带认证的加密模式，密文被篡改会在解密时直接失败， 不会出现"解出乱码还当成功"的中间态。
 *
 * <p>★ <b>IV 每次随机生成</b>（12 字节，GCM 的标准长度）：同一明文两次加密得到不同密文 —— 否则库被读走时， 攻击者一眼就能看出"这几个模型用的是同一个 key"。 IV
 * 不是秘密，与密文分两列存即可。
 *
 * <p>★ <b>纪律</b>：任何视图都不得回传明文（下一段的 DTO 里不放 {@code apiKey} 字段， 与 {@code UserView} 不含 {@code
 * passwordHash} 同理）。这里的方法只在「写入前加密」「调用供应商前解密」两个场景被调用， 解密结果不进日志、不进响应。
 */
public class ApiKeyCipher {

  /** AES-256：密钥 32 字节。 */
  private static final int KEY_BYTES = 32;

  /** GCM 标准 IV 长度（字节）。 */
  private static final int IV_BYTES = 12;

  /** GCM 认证标签长度（比特）。128 是 JDK 的推荐值。 */
  private static final int TAG_BITS = 128;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /** 一个已加密的 API key：密文与 IV 都是 base64 文本，可直接落库。 */
  public record Sealed(String cipherBase64, String ivBase64) {}

  /**
   * @param secretBase64 Base64 编码的 32 字节密钥（配置 {@code app.model.api-key-secret}）。 ★
   *     解析失败或长度不符必须抛<strong>可读异常</strong> —— 让运维一眼看出"密钥配错了"， 而不是在第一次加密时抛一个 {@code
   *     InvalidKeyException} 或更糟的"解不开"。
   */
  public ApiKeyCipher(String secretBase64) {
    this.key = parseKey(secretBase64);
  }

  private static SecretKeySpec parseKey(String secretBase64) {
    if (secretBase64 == null || secretBase64.isBlank()) {
      throw new IllegalArgumentException(
          "app.model.api-key-secret 未配置 —— 它是 AES-256 密钥（Base64 的 32 字节），生产环境必须用环境变量覆盖");
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(secretBase64.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "app.model.api-key-secret 不是合法的 Base64 —— 它应是 Base64 编码的 32 字节 AES-256 密钥", e);
    }
    if (raw.length != KEY_BYTES) {
      throw new IllegalArgumentException(
          "app.model.api-key-secret 解码后是 "
              + raw.length
              + " 字节，AES-256 要求 "
              + KEY_BYTES
              + " 字节 —— 请生成 32 字节随机密钥后再 Base64 编码");
    }
    return new SecretKeySpec(raw, "AES");
  }

  /**
   * 加密。每次调用生成随机 IV —— 同一明文两次加密得到不同密文。
   *
   * @return 密文与 IV（均为 base64）。
   */
  public Sealed encrypt(String plaintext) {
    if (plaintext == null) {
      throw new IllegalArgumentException("待加密的 API key 不能为 null");
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return new Sealed(
          Base64.getEncoder().encodeToString(ciphertext), Base64.getEncoder().encodeToString(iv));
    } catch (GeneralSecurityException e) {
      // 这一层的失败是环境问题（JCE 不可用等），不是调用方传参问题
      throw new IllegalStateException("API key 加密失败（JCE 环境异常）", e);
    }
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
    if (cipherBase64 == null || cipherBase64.isBlank()) {
      throw new IllegalArgumentException("密文为空 —— 无法解密 API key");
    }
    if (ivBase64 == null || ivBase64.isBlank()) {
      throw new IllegalArgumentException("IV 为空 —— 无法解密 API key（IV 与密文必须成对保存）");
    }
    try {
      byte[] ciphertext = Base64.getDecoder().decode(cipherBase64.trim());
      byte[] iv = Base64.getDecoder().decode(ivBase64.trim());
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] plaintext = cipher.doFinal(ciphertext);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      // GCM 的认证失败（错的密钥 / 密文被篡改）与 base64 不合法都落到这里：
      // 对使用者来说下一步动作完全一样 —— 检查密钥配置或重新录入 key
      throw new IllegalArgumentException(
          "API key 解密失败：密钥不正确或密文已损坏，请检查 app.model.api-key-secret 或重新录入 key", e);
    }
  }
}
