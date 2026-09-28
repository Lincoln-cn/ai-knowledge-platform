package cn.lincoln.aiplatform.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 验证码关闭时的启动告警。
 *
 * <p>★ 为什么必须有这条：「验证码不按要求输入也能通过」被当成产品 bug， 真因是起环境时用了 {@code --app.security.captcha.enabled=false}，
 * 而<strong>关闭状态完全不可见</strong> —— 后端静默跳过、前端照常渲染输入框。 一个"界面在要验证码、后端不认"的状态，比"明确没有验证码"更误导人。
 *
 * <p>★ 只在启动时打一条（{@link ApplicationRunner}，与 {@link PermissionCatalogRunner} 同一形态），
 * <strong>不在每次登录时刷屏</strong> —— 登录路径上的重复警告既淹没日志，又等价于没警告。
 */
@Component
public class CaptchaDisabledWarningRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CaptchaDisabledWarningRunner.class);

  private final CaptchaProperties captchaProperties;

  public CaptchaDisabledWarningRunner(CaptchaProperties captchaProperties) {
    this.captchaProperties = captchaProperties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!captchaProperties.enabled()) {
      log.warn(
          "⚠️ 验证码校验已关闭（app.security.captcha.enabled=false）—— 等保测评项不可用，仅用于压测/自动化。"
              + "生产环境必须打开（默认就是 true）");
    }
  }
}
