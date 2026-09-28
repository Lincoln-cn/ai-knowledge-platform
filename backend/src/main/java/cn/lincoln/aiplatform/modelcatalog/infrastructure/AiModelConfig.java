package cn.lincoln.aiplatform.modelcatalog.infrastructure;

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
 * <p>⚠️ 这里**不挂任何 advisor**。检索增强、记忆、结构化输出都在 M3/M4b 落地，届时按 {@code 11} 的 order 约定（memory +200 / 我们 250
 * / tool +300）显式装配。 现在挂一个空的，只会让人误以为"advisor 已经配好了"。
 */
@Configuration
@EnableConfigurationProperties(ModelApiProperties.class)
public class AiModelConfig {

  @Bean
  ChatClient chatClient(ChatModel chatModel) {
    return ChatClient.builder(chatModel).build();
  }

  /**
   * API key 加解密器。
   *
   * <p>★ 显式建 bean 而不是 {@code @Component}：它的构造参数是密钥配置， 在这里一眼能看到"这东西手里有密钥"——扫包扫出来的安全感是假的。
   */
  @Bean
  ApiKeyCipher apiKeyCipher(ModelApiProperties properties) {
    return new ApiKeyCipher(properties.apiKeySecret());
  }
}
