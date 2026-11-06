package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.domain.CryptoPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型接入的装配 —— 属性类与 API key 加解密器。
 *
 * <p>为什么在 {@code modelcatalog} 的 {@code infrastructure} 包：它是"外部适配器" （{@code 01 §6.2} 给 {@code
 * infrastructure} 的职责），而模型接入正是被隔离在这个模块里的东西 （{@code 01 §二} 第 3 行）。
 *
 * <p>★ 2026-11-06 删掉了原来那个 {@code @Bean ChatClient chatClient(ChatModel)}：它依赖 OpenAI starter 自动配置出来的
 * {@code ChatModel}，而那个 starter 现在只在 {@code test} scope（生产代码零使用已核实）。 代价与取舍写在 {@code pom.xml}
 * 那一节的注释里。生产代码一律 {@code ChatClient.builder(model).build()} 现建 —— advisor 链在 {@link
 * SpringAiChatAdapter} 里按 order 装配（{@code SafeGuardChatAdvisor} 100 / {@code AdvisorChainTrace}
 * 150），流式与非流式两个出口都挂。
 */
@Configuration
@EnableConfigurationProperties({ModelApiProperties.class, EgressProperties.class})
public class AiModelConfig {

  /**
   * API key 加解密器。
   *
   * <p>★ 显式建 bean 而不是 {@code @Component}：它的构造参数是密钥配置， 在这里一眼能看到"这东西手里有密钥"——扫包扫出来的安全感是假的。
   *
   * <p>★ 自 {@code #74} 起它收的是<strong>端口</strong>（{@code CryptoPort}），算法实现只在 {@code
   * platform.infrastructure} 落地一次 —— 换国密时这个 bean 一行不改。
   */
  @Bean
  ApiKeyCipher apiKeyCipher(CryptoPort cryptoPort) {
    return ApiKeyCipher.of(cryptoPort);
  }
}
