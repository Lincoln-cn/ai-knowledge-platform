package cn.lincoln.aiplatform.platform.infrastructure;

import cn.lincoln.aiplatform.platform.domain.CryptoPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link CryptoPort} 的 JDK 实现（AES-256-GCM + SHA-256）。
 *
 * <p>★ <b>为什么用 JDK 自带</b>：不引第三方加密库。GCM 是带认证的加密模式，密文被篡改会在解密时直接失败，不会出现"解出乱码还当成功"的中间态。
 *
 * <p>★ <b>为什么 IV 每次随机</b>（12 字节，GCM 标准长度）：同一明文两次加密得到不同密文 —— 否则库被读走时，攻击者一眼就能看出"这几个模型用的是同一个 key"。
 *
 * <p>★ <b>密钥在构造时校验</b>（不是第一次加密时才校验）：配错密钥的运维应该在<strong>启动</strong>看到可读异常，而不是在第一次调用供应商时看到 {@code
 * InvalidKeyException}，或者更糟 —— "解不开"。
 *
 * <p>★ 上国密时的动作：新增一个实现类（如 {@code SmCryptoPort}）并把 {@link CryptoConfig} 的 {@code @Bean} 换过去。调用点不动。
 */
public class AesGcmCryptoPort implements CryptoPort {

  /** AES-256：密钥 32 字节。 */
  private static final int KEY_BYTES = 32;

  /** GCM 标准 IV 长度（字节）。 */
  private static final int IV_BYTES = 12;

  /** GCM 认证标签长度（比特）。128 是 JDK 的推荐值。 */
  private static final int TAG_BITS = 128;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  /** 摘要算法。换国密时这里换实现类，不是换这个常量。 */
  private static final String DIGEST_ALGORITHM = "SHA-256";

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param secretBase64 Base64 编码的 32 字节密钥（配置 {@code app.model.api-key-secret}）。
   * @throws IllegalArgumentException 未配置 / 不是合法 Base64 / 长度不是 32 字节 —— 三种都给可读的"下一步"
   */
  public AesGcmCryptoPort(String secretBase64) {
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

  @Override
  public String digestHex(String plain) {
    if (plain == null) {
      throw new IllegalArgumentException("待摘要的内容不能为 null");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
      return HexFormat.of().formatHex(digest.digest(plain.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // 只有 JVM 残缺才会到这里：不是调用方的问题
      throw new IllegalStateException("JVM 不支持 " + DIGEST_ALGORITHM, e);
    }
  }

  @Override
  public Sealed seal(String plaintext) {
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

  @Override
  public String open(String cipherBase64, String ivBase64) {
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
      // GCM 的认证失败（错的密钥 / 密文被篡改）与 Base64 不合法都落到这里：
      // 对使用者来说下一步动作完全一样 —— 检查密钥配置或重新录入 key
      throw new IllegalArgumentException(
          "API key 解密失败：密钥不正确或密文已损坏，请检查 app.model.api-key-secret 或重新录入 key", e);
    }
  }
}
