package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.domain.CryptoPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型接入的装配 —— 把 Spring AI 的 {@link ChatModel} 收成一个<strong>单例</strong> {@link ChatClient}。
 *
 * <p>为什么在 {@code modelcatalog} 的 {@code infrastructure} 包：它是"外部适配器" （{@code 01 §6.2} 给 {@code
 * infrastructure} 的职责），而模型接入正是被隔离在这个模块里的东西 （{@code 01 §二} 第 3 行）。
 *
 * <p>★ 为什么是单例、且由我们显式建：{@code ChatClient} 每次 {@code builder().build()} 都会 重新装配 advisor 链。等 M3/M4b
 * 挂上检索 advisor（{@code 11 §9} 的 order 250 那一段）之后， 每个请求各建一个客户端等于把整条 advisor 链重建一遍 ——
 * 这是那种"功能全对、只是慢"的问题， 最难在测试里发现。
 *
 * <p>⚠️ 这个 bean **不挂 advisor**：它只是 modelcatalog 暴露的通用客户端。真正的 advisor 链在 {@link SpringAiChatAdapter}
 * 里按 order 装配（{@code SafeGuardChatAdvisor} 100 / {@code AdvisorChainTrace} 150）；流式路径还没接这条链（{@code
 * #62} 的剩余面）。
 */
@Configuration
@EnableConfigurationProperties({ModelApiProperties.class, EgressProperties.class})
public class AiModelConfig {

  @Bean
  ChatClient chatClient(ChatModel chatModel) {
    return ChatClient.builder(chatModel).build();
  }

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
