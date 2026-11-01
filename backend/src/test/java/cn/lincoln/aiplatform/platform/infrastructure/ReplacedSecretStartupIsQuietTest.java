package cn.lincoln.aiplatform.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 反面的一半：<strong>换过密钥的部署不该出现任何一条密钥警告</strong>（{@code #272} 判据 ②）。
 *
 * <p>★ 为什么要和"会报"那条 <em>同样的级别</em>（都真起服务）：「永远报」与「该报才报」在 {@link SampleSecretStartupWarningTest}
 * 那一条上读数完全一样 —— 只要它每次都报警，那条测试仍然绿。 判据 ② 原话就是"好的不报必须与坏的会报一起验"。
 *
 * <p>★ 为什么还要这一条：纯函数层已经有三条断言（{@link StartupSettingsCheckTest}），但它们只证明
 * "把示例值喂进去会返回一句话"。真正会失效的地方是<em>接线</em> —— 自检换了签名、{@code @Value} 取的键名写错、 或者 runner 根本没被注册成
 * bean，纯函数测试全都照样绿，而生产上一条警告都不会出现。
 *
 * <p>★ 这里刻意断言的是**日志痕迹**，不是返回值：这条自检唯一的输出就是日志， "没打出来"与"没检查"在返回值上完全一样（{@code #216} 那次学到的同一条）。
 */
@Testcontainers
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      // 故意用仓库里的示例值：这正是"新部署照抄 defaults"时的真实状态。
      // 随便一串"换过"的值即可，但**必须是合法 Base64 且解码后 32 字节**：
      // `AesGcmCryptoPort` 与 `JwtProperties` 都是按这个形状校验的，长度不对会让整个上下文起不来
      // —— 第一次提交用的就是一串"看着像密钥"的明文字符串，整包直接红在 Failed to load ApplicationContext。
      // 判据是"不等于仓库里的示例值"，不是密钥强度（强度是部署方的事）。
      "app.security.jwt.secret=cmVwbGFjZWQtand0LXNlY3JldC1kby1ub3QtdXNlLWluLXByb2QtMzhi",
      "app.model.api-key-secret=cmVwbGFjZWQtYXBpLWtleS1zZWNyZXQtMzJieXRlcyE="
    })
class ReplacedSecretStartupIsQuietTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Test
  void startupSaysNothingAboutSecretsWhenTheyAreReplaced(CapturedOutput output) {
    String logs = output.getAll();

    assertThat(logs).as("换过密钥的部署必须看到『通过』那一行 —— 它是这一族自检唯一的正向读数").contains("配置自检通过：向量化并发");

    assertThat(logs)
        .as("★ 换过密钥之后还出现密钥警告，等于这条自检永远报警 —— 那样『坏的会报』就证明不了任何事")
        .doesNotContain("app.security.jwt.secret 用的还是仓库里的示例值")
        .doesNotContain("app.model.api-key-secret 用的还是仓库里的示例值");
  }
}
