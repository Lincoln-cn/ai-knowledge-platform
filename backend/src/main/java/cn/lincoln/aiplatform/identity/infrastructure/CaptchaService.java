package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.application.CaptchaView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * 图形验证码：生成、渲染、一次性校验。
 *
 * <p>★ <strong>为什么自己画而不引第三方（kaptcha / easy-captcha）</strong>： 需要的东西只有三件 —— 随机字符、Java2D
 * 画几张图、一张带过期的键值表。 JDK 自带的 {@code java.awt} 与 Caffeine 已经全给了。引一个验证码库会同时引入 它的字体配置、Servlet
 * 依赖与更新风险，而收益只是省下这几十行。 （判断依据同 {@code 09 §一}：引入一个组件的前提是它替你解决了一个"你自己写会错"的问题。）
 *
 * <p>★ 三道让它不至于形同虚设的措施：
 *
 * <ol>
 *   <li><strong>一次性消费</strong>：{@link #verify} 无论对错都作废该 id。 否则攻击者可以拿着同一个 id 反复试 —— 4 位验证码只有几十万种组合，
 *       一个不过期的 id 等于没有验证码。
 *   <li><strong>排除易混字符</strong>（0/O、1/I/L）：这不是安全问题，是体验问题 ——
 *       用户看不清就刷新，刷新的成本比输错的成本低，而他会开始怀疑"验证码是不是坏了"。
 *   <li><strong>短 TTL + 容量上限</strong>：2 分钟够输入一次；容量上限防止 "不断取验证码不登录"把内存撑满。
 * </ol>
 */
@Service
public class CaptchaService {

  private static final int WIDTH = 120;
  private static final int HEIGHT = 40;
  private static final int CODE_LENGTH = 4;
  private static final int MAX_STORED = 10_000;

  /**
   * 字符集。★ 去掉了 {@code 0 O 1 I L} —— 它们在 40 像素高的图上几乎无法区分。 剩下的 31 个字符的 4 位组合约 92 万种，配合"一次性 + 2
   * 分钟"已经足够。
   */
  private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

  private final Cache<String, String> store;
  private final SecureRandom random = new SecureRandom();

  public CaptchaService(CaptchaProperties properties) {
    Duration ttl = properties.ttl();
    this.store = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(MAX_STORED).build();
  }

  /** 发一张新验证码。 */
  public CaptchaView issue() {
    String code = randomCode();
    String id = UUID.randomUUID().toString();
    store.put(id, code);
    return new CaptchaView(id, render(code));
  }

  /**
   * 校验并<strong>消费</strong>验证码。
   *
   * @return 是否通过。id 缺失、已过期、已用过、或字符不匹配都返回 false
   */
  public boolean verify(String id, String input) {
    if (id == null || input == null || input.isBlank()) {
      return false;
    }
    String expected = store.getIfPresent(id);
    // ★ 无论对错都作废：一次性是这道防线的关键，不是附加要求
    store.invalidate(id);
    return expected != null && expected.equalsIgnoreCase(input.trim());
  }

  /**
   * ⚠️ <strong>仅供测试</strong>：读出某个 id 的验证码明文。
   *
   * <p>它存在的原因很实际：验证码的设计目标就是"只有人能读"，于是自动化测试无法 通过正常途径得知答案 —— 而"登录接口在验证码开启时的行为"是我们确实需要覆盖的。
   * <strong>生产代码不要调用它</strong>：任何一处调用都等于让验证码失效。
   */
  public String peekCodeForTesting(String id) {
    return store.getIfPresent(id);
  }

  // ────────────────────────── 渲染 ──────────────────────────

  private String randomCode() {
    StringBuilder out = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      out.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return out.toString();
  }

  private String render(String code) {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, WIDTH, HEIGHT);

      // 干扰线：浅色、随机 —— 目的是让"截图后自动识别"变难，但不要影响人眼
      for (int i = 0; i < 6; i++) {
        graphics.setColor(randomColor(160, 220));
        graphics.drawLine(
            random.nextInt(WIDTH),
            random.nextInt(HEIGHT),
            random.nextInt(WIDTH),
            random.nextInt(HEIGHT));
      }

      // 噪点
      for (int i = 0; i < 80; i++) {
        graphics.setColor(randomColor(150, 235));
        graphics.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
      }

      // 字符：深色 + 轻微旋转
      graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
      int step = WIDTH / (CODE_LENGTH + 1);
      for (int i = 0; i < code.length(); i++) {
        graphics.setColor(randomColor(20, 110));
        double angle = (random.nextDouble() - 0.5) * 0.5;
        int x = step * (i + 1) - 8;
        int y = 30;
        graphics.rotate(angle, x, y);
        graphics.drawString(String.valueOf(code.charAt(i)), x, y);
        graphics.rotate(-angle, x, y);
      }
    } finally {
      graphics.dispose();
    }

    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    } catch (IOException e) {
      throw new UncheckedIOException("渲染验证码图片失败", e);
    }
  }

  private Color randomColor(int min, int max) {
    int span = max - min;
    return new Color(
        min + random.nextInt(span), min + random.nextInt(span), min + random.nextInt(span));
  }
}
