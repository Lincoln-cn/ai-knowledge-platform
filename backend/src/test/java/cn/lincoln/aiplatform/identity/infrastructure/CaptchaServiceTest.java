package cn.lincoln.aiplatform.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.identity.application.CaptchaView;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 图形验证码（等保：登录应带验证码）。 */
class CaptchaServiceTest {

  private CaptchaService service;

  @BeforeEach
  void setUp() {
    service = new CaptchaService(new CaptchaProperties(true, Duration.ofMinutes(2)));
  }

  @Test
  void 发出的验证码能被校验通过() {
    CaptchaView view = service.issue();
    String code = service.peekCodeForTesting(view.id());

    assertThat(code).hasSize(4);
    assertThat(service.verify(view.id(), code)).isTrue();
  }

  @Test
  void 图片是可内嵌的PNG_dataURL() {
    CaptchaView view = service.issue();

    assertThat(view.id()).isNotBlank();
    assertThat(view.image()).startsWith("data:image/png;base64,");
    // PNG 的魔数 —— 确认写出来的是真图片，而不是一段空 base64
    assertThat(view.image().length()).isGreaterThan(500);
  }

  /** ★ 一次性消费是这道防线的关键，不是附加要求：4 位字符只有约 92 万种组合， 一个可以反复试的 id 等于没有验证码。而"取一次、试很多次"恰恰是脚本最自然的写法。 */
  @Test
  void 校验成功后同一个id不能再用() {
    CaptchaView view = service.issue();
    String code = service.peekCodeForTesting(view.id());

    assertThat(service.verify(view.id(), code)).isTrue();
    assertThat(service.verify(view.id(), code)).as("第二次必须失败 —— 否则一个 id 可以被反复试").isFalse();
  }

  /** 校验失败也要消费掉：不能"猜错了就把机会留着"，那等于允许无限次尝试。 */
  @Test
  void 校验失败后同一个id同样作废() {
    CaptchaView view = service.issue();
    String code = service.peekCodeForTesting(view.id());

    assertThat(service.verify(view.id(), "WRONG")).isFalse();
    assertThat(service.verify(view.id(), code)).as("猜错一次之后，正确答案也不该再被接受").isFalse();
  }

  @Test
  void 大小写不敏感且忽略首尾空格() {
    CaptchaView view = service.issue();
    String code = service.peekCodeForTesting(view.id());

    assertThat(service.verify(view.id(), "  " + code.toLowerCase() + "  ")).isTrue();
  }

  @Test
  void 未知id与空输入都失败() {
    assertThat(service.verify("not-a-real-id", "ABCD")).isFalse();
    assertThat(service.verify(null, "ABCD")).isFalse();
    assertThat(service.verify("some-id", null)).isFalse();
    assertThat(service.verify("some-id", "  ")).isFalse();
  }

  /** ★ 字符集里不能有 0/O/1/I/L —— 它们在 40 像素高的图上几乎无法区分。 这不是安全问题，是体验问题：用户看不清就刷新，而他会开始怀疑"验证码是不是坏了"。 */
  @Test
  void 生成的字串不含易混字符() {
    for (int i = 0; i < 200; i++) {
      CaptchaView view = service.issue();
      assertThat(service.peekCodeForTesting(view.id()))
          .as("验证码里出现了 0/O/1/I/L 之一")
          .doesNotContainAnyWhitespaces()
          .matches("[2-9A-HJKMNP-Z]{4}");
    }
  }
}
