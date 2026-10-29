package cn.lincoln.aiplatform.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.lincoln.aiplatform.support.PostgresImage;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 「响应已提交之后才抛异常」时，<b>原始异常必须进日志</b>（`#216` 的判据 1）。
 *
 * <h3>★★ 这条测试钉住的是哪一件事</h3>
 *
 * <p>不是"500 不会再发生"（那是触发层，另算），而是：<b>当一次请求已经写出响应头/体之后才失败时，日志里能不能看见 原始异常的类型与堆栈</b>。今天（没有 {@link
 * CommittedResponseExceptionResolver}）的答案是"看不见" —— 真栈上看到的是 Tomcat 的二次 NPE（{@code
 * MimeHeaders.removeHeader} 里 {@code this.headers[i] == null}）， 原始异常被它顶掉，响应体一个字节都没有，任何"偶发
 * 500"都不可诊断。
 *
 * <h3>为什么用真 Tomcat + 一个只在测试里存在的控制器</h3>
 *
 * <p>"提交之后"这件事在 Mock 的 response 上是假的（{@code MockHttpServletResponse} 允许随便写头），
 * 而真实缺陷恰恰发生在真实容器里（Tomcat 在提交后清掉了 header 槽位）。所以要：
 *
 * <ol>
 *   <li>真起服务（{@code RANDOM_PORT}）；
 *   <li>用一个测试专用端点：先写出 16KB 并 {@code flush()}（越过容器 8KB 缓冲 ⇒ 响应提交）， <b>然后再抛异常</b>；
 *   <li>断言 resolver 记下的那条日志里有<b>原始异常类名</b>与<b>异常对象本身</b>（带堆栈）， 而不是"某个包装后的错"。
 * </ol>
 *
 * <h3>反证（谁把这条路拆了，这条会红）</h3>
 *
 * <p>把 {@link CommittedResponseExceptionResolverConfig#extendHandlerExceptionResolvers} 里的 {@code
 * resolvers.add(0, …)} 注释掉 ⇒ 请求会落回默认异常处理路径，日志里不再有原始异常 ⇒ 本测试变红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
@Import(CommittedResponseExceptionResolverLogTest.TestEndpoint.class)
class CommittedResponseExceptionResolverLogTest {

  /** 与真栈同镜像（pgvector + 固定 tag），避免"测试绿、真栈红"。 */
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String ORIGINAL_MESSAGE = "模拟：响应已提交之后的原始异常";

  private static final String LOG_HEADLINE = "响应已提交之后仍抛出异常";

  @LocalServerPort private int port;

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attachAppender() {
    appender.start();
    ((Logger) LoggerFactory.getLogger(CommittedResponseExceptionResolver.class))
        .addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    ((Logger) LoggerFactory.getLogger(CommittedResponseExceptionResolver.class))
        .detachAppender(appender);
  }

  @Test
  void anExceptionThrownAfterTheResponseCommittedStillReachesTheLog() throws Exception {
    HttpResponse<String> response = get("/test-only/boom-after-commit");

    // ① 响应确实已经提交出去了：客户端读到的是那段 padding，既不是 JSON 包络、也不是空体。
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains(": padding");

    // ② 原始异常在日志里：三条日志都要有（原始异常被容器二次错误顶掉时一条都没有）。
    List<ILoggingEvent> events =
        appender.list.stream()
            .filter(event -> event.getFormattedMessage().contains(LOG_HEADLINE))
            .collect(Collectors.toList());
    assertThat(events).as("响应已提交之后的异常必须留下一条可定位的日志（否则任何偶发 500 都不可诊断）").hasSize(1);
    ILoggingEvent event = events.get(0);
    assertThat(event.getFormattedMessage())
        .contains(ORIGINAL_MESSAGE)
        .contains("IllegalStateException");
    assertThat(event.getThrowableProxy()).as("必须带堆栈：只记一句消息无法定位是哪一行抛的").isNotNull();
    assertThat(event.getThrowableProxy().getClassName())
        .as("记的必须是原始异常本身（不是容器包装出来的那个）")
        .isEqualTo(IllegalStateException.class.getName());
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .GET()
              .timeout(Duration.ofSeconds(20))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
  }

  /**
   * 测试专用端点：先写出 16KB + {@code flush()}（提交响应），<b>然后</b>抛异常。
   *
   * <p>★ 这段 padding 不是装饰：Tomcat 默认响应缓冲 8KB，不写满就不会真正提交，而"提交"正是这条链路的触发条件。
   */
  @RestController
  static class TestEndpoint {

    @GetMapping("/test-only/boom-after-commit")
    void boomAfterCommit(HttpServletResponse response) throws IOException {
      response.setContentType("text/event-stream;charset=UTF-8");
      response
          .getOutputStream()
          .write(
              (": padding ----------------------------------------\n\n".repeat(400))
                  .getBytes(StandardCharsets.UTF_8));
      response.getOutputStream().flush();
      throw new IllegalStateException(ORIGINAL_MESSAGE);
    }
  }

  /** 只让测试端点绕开鉴权（测试上下文里生效）。 */
  @TestConfiguration
  static class SecurityBypass {
    @Bean
    WebSecurityCustomizer ignoreTestEndpoints() {
      return web -> web.ignoring().requestMatchers("/test-only/**");
    }
  }
}
