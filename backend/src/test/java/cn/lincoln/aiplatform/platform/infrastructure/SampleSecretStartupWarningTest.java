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
 * 用<strong>真的把服务起起来</strong>这件事，证明密钥自检会说话（{@code #272} 判据 ①）。
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
      "app.security.jwt.secret=ZGV2LW9ubHktc2VjcmV0LWtleS1kby1ub3QtdXNlLWluLXByb2Q=",
      "app.model.api-key-secret=ZGV2LW9ubHktYXBpLWtleS1zZWNyZXQtMzJieXRlcyE="
    })
class SampleSecretStartupWarningTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Test
  void startupWarnsThatBothSecretsAreStillTheSamples(CapturedOutput output) {
    String logs = output.getAll();

    assertThat(logs)
        .as("启动自检必须真的把警告打出来 —— 纯函数绿不代表接线还对")
        .contains("配置自检发现")
        .contains("app.security.jwt.secret 用的还是仓库里的示例值")
        .contains("app.model.api-key-secret 用的还是仓库里的示例值");

    // ★ 两句都要点名"换成哪个环境变量"，否则运维只知道有问题、不知道改哪儿。
    assertThat(logs).contains("JWT_SECRET").contains("MODEL_API_KEY_SECRET");

    // ★ 反向：这一轮不该出现"通过"那句 —— 出现了就说明两台机器上"通过"和"警告"能同时为真。
    assertThat(logs).as("示例密钥下不该同时打出『配置自检通过』").doesNotContain("配置自检通过：向量化并发");
  }
}
