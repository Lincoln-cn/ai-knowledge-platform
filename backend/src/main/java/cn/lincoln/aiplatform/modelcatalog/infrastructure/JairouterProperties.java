package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 快速绑定 JAiRouter 的默认地址。
 *
 * <p>★ 单独一组配置，<strong>不复用</strong> {@code spring.ai.openai.base-url}：后者在开发环境默认指向 Ollama 的 {@code
 * 11434}，拿它做“一键绑 JAiRouter”会把 Ollama 绑成网关（名字、认证、文档全错）。
 */
@Component
@ConfigurationProperties(prefix = "app.providers.jairouter")
public class JairouterProperties {

  /** JAiRouter 的 OpenAI 兼容入口（含 {@code /v1}）。 */
  private String baseUrl = "http://localhost:8080/v1";

  /** 控制台地址（创建 API Key 的地方）。 */
  private String consoleUrl = "http://localhost:8080/admin/";

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getConsoleUrl() {
    return consoleUrl;
  }

  public void setConsoleUrl(String consoleUrl) {
    this.consoleUrl = consoleUrl;
  }
}
