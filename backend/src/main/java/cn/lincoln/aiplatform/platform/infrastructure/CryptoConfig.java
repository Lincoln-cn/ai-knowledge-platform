package cn.lincoln.aiplatform.platform.infrastructure;

import cn.lincoln.aiplatform.platform.domain.CryptoPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 密码学端口的装配（{@code #74}）。
 *
 * <p>★ 显式建 bean 而不是 {@code @Component}：它的构造参数是密钥，在这里一眼能看到"这东西手里有密钥"—— 扫包扫出来的安全感是假的（与 {@code
 * AiModelConfig} 里建 {@code ApiKeyCipher} 同一个理由）。
 *
 * <p>★ 用 {@code @Value} 直接取配置而<strong>不</strong>引用 {@code modelcatalog} 的 {@code
 * ModelApiProperties}：{@code platform} 是底座，让它反向依赖任何一个业务模块都会把 {@code ModuleBoundaryTest}
 * 想守住的方向弄反。配置键 {@code app.model.api-key-secret} 是两条路共同的源头。
 *
 * <p>★ <strong>上国密时只改这里的一行</strong>（把 {@link AesGcmCryptoPort} 换成 {@code SmCryptoPort}），调用点不动 ——
 * 这就是 {@link CryptoPort} 存在的意义。
 */
@Configuration
public class CryptoConfig {

  /** 密钥只在平台层落地一次；各业务模块拿到的都是端口，不接触算法。 */
  @Bean
  CryptoPort cryptoPort(@Value("${app.model.api-key-secret}") String secretBase64) {
    return new AesGcmCryptoPort(secretBase64);
  }
}
