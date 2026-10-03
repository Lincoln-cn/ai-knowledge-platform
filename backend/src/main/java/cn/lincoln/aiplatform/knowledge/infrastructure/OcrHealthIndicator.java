package cn.lincoln.aiplatform.knowledge.infrastructure;

import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * OCR 健康指示器（第 9 周 · 0.3.1）—— ★ <strong>部署验收的那一面</strong>。
 *
 * <h3>★ 它为什么是"离线/可移植"这件事的关键一环</h3>
 *
 * <p>OCR 引擎是<strong>系统级资产</strong>（不像 jar 跟着构建走）。装没装、语言包拷没拷，
 * 决定了"扫描件能不能入库"；而在没有本指示器之前，这个答案只能靠**人工点一次上传**才知道。
 *
 * <p>⇒ {@code GET /actuator/health} 的 {@code components.ocr} 直接回答它：
 *
 * <pre>
 * "ocr": { "status": "UP",       "details": { "available": true,  "languages": ["chi_sim","eng"], ... } }
 * "ocr": { "status": "DEGRADED", "details": { "available": false, "reason": "找不到 tesseract …" } }
 * </pre>
 *
 * <p>★ 不可用时用自定义状态 {@code DEGRADED} 而不是 {@code DOWN}：
 *
 * <ul>
 *   <li>{@code DOWN} 会让整个应用被判为不健康 —— 而"没装 OCR"不影响文本型文档、不影响登录、 不影响检索；把它报成服务挂掉是另一种失真
 *   <li>Boot 默认只把 {@code DOWN}/{@code OUT_OF_SERVICE} 映射成 503，{@code DEGRADED} 仍是 <b>200</b> ⇒
 *       探针不会因此重启容器
 *   <li>但它<strong>显式地不正常</strong>，一眼能从 health JSON 里看出来，适合写进交付验收清单
 * </ul>
 *
 * <p>★ 因此部署验收的正确判据是 <code>components.ocr.details.available == true</code>， 不是"health 整体是不是 UP"。
 *
 * <p>★ 包名口径（Boot 4 踩过）：健康 API 在 Boot 4 搬到了 {@code org.springframework.boot.health.contributor} ——
 * 旧路径 {@code org.springframework.boot.actuate.health} 里只剩端点内部实现，{@code Health}/{@code
 * HealthIndicator} 都不在了，照 Boot 3 的包名写会编译不过。
 */
@Component("ocr")
public class OcrHealthIndicator implements HealthIndicator {

  private final OcrEngine engine;

  public OcrHealthIndicator(OcrEngine engine) {
    this.engine = engine;
  }

  @Override
  public Health health() {
    OcrEngine.Status status = engine.status();
    Health.Builder builder = status.available() ? Health.up() : Health.status("DEGRADED");
    builder
        .withDetail("available", status.available())
        .withDetail("languages", List.copyOf(status.languages()))
        .withDetail("tesseractPath", status.tesseractPath())
        .withDetail("tessdataPath", engine.effectiveTessdataPath().orElse("(未配置)"))
        .withDetail("dpi", engine.properties().dpi());
    if (!status.available()) {
      builder.withDetail("reason", status.reason()).withDetail("hint", status.hint());
    }
    return builder.build();
  }
}
