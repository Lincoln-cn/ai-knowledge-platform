package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiKeyCipher}（第 4 周 · 0.1.3 测试项 ②）。
 *
 * <p>★ 本段唯一的安全取舍，所以四条性质一条都不能少： 明文≠密文 · 解密回原文 · 同一明文两次密文不同（IV 随机） · 错的密钥解不开。 少任何一条，"加密落库"就只是个装饰（例如固定
 * IV 的 ECB 模式会同时挂在第 3 条上）。
 */
class ApiKeyCipherTest {

  /** 32 字节密钥的 Base64（AES-256）。 */
  private static final String KEY =
      Base64.getEncoder()
          .encodeToString(
              "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  private static final String OTHER_KEY =
      Base64.getEncoder()
          .encodeToString(
              "fedcba9876543210fedcba9876543210".getBytes(java.nio.charset.StandardCharsets.UTF_8));

  private static final String PLAINTEXT = "sk-very-secret-model-api-key-123456";

  @Test
  void 明文不等于密文() {
    ApiKeyCipher cipher = new ApiKeyCipher(KEY);
    ApiKeyCipher.Sealed sealed = cipher.encrypt(PLAINTEXT);

    assertThat(sealed.cipherBase64()).isNotEqualTo(PLAINTEXT);
    // base64 文本本身也不该是明文的 base64
    assertThat(sealed.cipherBase64())
        .isNotEqualTo(Base64.getEncoder().encodeToString(PLAINTEXT.getBytes()));
  }

  @Test
  void 解密回到原文() {
    ApiKeyCipher cipher = new ApiKeyCipher(KEY);
    ApiKeyCipher.Sealed sealed = cipher.encrypt(PLAINTEXT);

    assertThat(cipher.decrypt(sealed.cipherBase64(), sealed.ivBase64())).isEqualTo(PLAINTEXT);
  }

  @Test
  void 同一明文两次加密得到不同密文_IV随机() {
    ApiKeyCipher cipher = new ApiKeyCipher(KEY);
    ApiKeyCipher.Sealed first = cipher.encrypt(PLAINTEXT);
    ApiKeyCipher.Sealed second = cipher.encrypt(PLAINTEXT);

    assertThat(first.cipherBase64())
        .as("IV 不随机 ⇒ 密文可对比，库被读走就能看出哪几个模型共用一个 key")
        .isNotEqualTo(second.cipherBase64());
    assertThat(first.ivBase64()).as("两次的 IV 也必须不同").isNotEqualTo(second.ivBase64());

    // 但两次都能解回同一明文
    assertThat(cipher.decrypt(first.cipherBase64(), first.ivBase64())).isEqualTo(PLAINTEXT);
    assertThat(cipher.decrypt(second.cipherBase64(), second.ivBase64())).isEqualTo(PLAINTEXT);
  }

  @Test
  void 错的密钥解不开() {
    ApiKeyCipher encoder = new ApiKeyCipher(KEY);
    ApiKeyCipher wrongKey = new ApiKeyCipher(OTHER_KEY);
    ApiKeyCipher.Sealed sealed = encoder.encrypt(PLAINTEXT);

    assertThatThrownBy(() -> wrongKey.decrypt(sealed.cipherBase64(), sealed.ivBase64()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("解密失败");
  }

  @Test
  void 密文被篡改解不开() {
    ApiKeyCipher cipher = new ApiKeyCipher(KEY);
    ApiKeyCipher.Sealed sealed = cipher.encrypt(PLAINTEXT);
    byte[] raw = Base64.getDecoder().decode(sealed.cipherBase64());
    raw[0] ^= 0x01; // 翻一个比特
    String tampered = Base64.getEncoder().encodeToString(raw);

    assertThatThrownBy(() -> cipher.decrypt(tampered, sealed.ivBase64()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("解密失败");
  }

  // ────────────────────────── 密钥解析：失败要有可读异常 ──────────────────────────

  @Test
  void 密钥为空抛可读异常() {
    assertThatThrownBy(() -> new ApiKeyCipher(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.model.api-key-secret");
    assertThatThrownBy(() -> new ApiKeyCipher("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.model.api-key-secret");
  }

  @Test
  void 密钥不是Base64抛可读异常() {
    assertThatThrownBy(() -> new ApiKeyCipher("这不是 base64 !!!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Base64");
  }

  @Test
  void 密钥长度不符抛可读异常() {
    // 16 字节 ⇒ AES-128，但本项目钉死 AES-256（32 字节）
    String tooShort = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());
    assertThatThrownBy(() -> new ApiKeyCipher(tooShort))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
  }

  // ────────────────────────── 解密入参 ──────────────────────────

  @Test
  void 解密时密文或IV为空抛可读异常() {
    ApiKeyCipher cipher = new ApiKeyCipher(KEY);
    assertThatThrownBy(() -> cipher.decrypt(null, "aXY="))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("密文");
    assertThatThrownBy(() -> cipher.decrypt("Y2lwaGVy", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IV");
  }

  @Test
  void 待加密明文为null抛可读异常() {
    ApiKeyCipher cipher = new ApiKeyCipher(KEY);
    assertThatThrownBy(() -> cipher.encrypt(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }

  @Test
  void 空字符串密钥也能加解密往返() {
    // 供应商可能给出空 key（例如 Ollama 不校验 key）—— 应当能存能取，而不是抛 NPE
    ApiKeyCipher cipher = new ApiKeyCipher(KEY);
    ApiKeyCipher.Sealed sealed = cipher.encrypt("");
    assertThat(cipher.decrypt(sealed.cipherBase64(), sealed.ivBase64())).isEmpty();
  }
}
