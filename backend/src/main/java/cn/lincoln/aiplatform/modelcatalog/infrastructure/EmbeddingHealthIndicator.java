package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 向量化的健康指示器（`#172`）—— ★ **"进程活着"与"向量化可用"是两件事**的读数。
 *
 * <h3>为什么必须单独一个指示器</h3>
 *
 * <p>实测过一个很典型的现场：文档解析完永远停在 {@code PENDING_EMBEDDING}、检索永远 0 命中， 而 {@code /actuator/health} 一直是
 * <b>UP</b> —— 因为向量化依赖的是**另一个进程** （Ollama / 桩，`11434`），它没起时应用本身确实健康。
 *
 * <p>⇒ 从此这个问题在 health JSON 里**一眼可见**：
 *
 * <pre>
 * "embedding": { "status": "UP",       "details": { "mode": "local-onnx", "ready": true,  "model": "bge-small-zh-v1.5" } }
 * "embedding": { "status": "DEGRADED", "details": { "mode": "local-onnx", "ready": false, "reason": "模型文件缺失：…" } }
 * </pre>
 *
 * <h3>★ 为什么不可用时是 {@code DEGRADED} 而不是 {@code DOWN}</h3>
 *
 * <p>与 {@link cn.lincoln.aiplatform.knowledge.infrastructure.OcrHealthIndicator} 逐字同一条理由：
 *
 * <ul>
 *   <li>{@code DOWN} 会让整个应用被判为不健康，而"向量化不可用"不影响登录、不影响浏览知识库、 不影响已有分段 —— 报成服务挂掉是另一种失真；
 *   <li>Boot 默认只把 {@code DOWN}/{@code OUT_OF_SERVICE} 映射成 503，{@code DEGRADED} 仍是 <b>200</b> ⇒
 *       探针不会因此重启容器；
 *   <li>但它<strong>显式地不正常</strong>，一眼能从 health JSON 看出来 —— 适合写进交付验收清单。
 * </ul>
 *
 * <p>★ 因此验收判据是 {@code components.embedding.details.ready == true}， 不是"health 整体是不是 UP"。
 *
 * <p>★ 包名口径（Boot 4 踩过）：健康 API 在 Boot 4 是 {@code org.springframework.boot.health.contributor}， 照
 * Boot 3 的 {@code org.springframework.boot.actuate.health} 写会编译不过。
 */
@Component("embedding")
public class EmbeddingHealthIndicator implements HealthIndicator {

  /**
   * 本地 ONNX 是否启用。
   *
   * <p>★ 关掉它时向量化走上游服务，本指示器没有任何可判的东西 ⇒ 报 {@code UNKNOWN} 并说明"由上游决定"，而不是假装 UP（那会把"没在管"说成"是好的"）。
   */
  private final LocalOnnxEmbedding local;

  public EmbeddingHealthIndicator(LocalOnnxEmbedding local) {
    this.local = local;
  }

  @Override
  public Health health() {
    boolean filesPresent = local.modelFilesPresent();
    boolean ready = local.ready();
    String failure = local.loadFailure();

    Health.Builder builder;
    if (ready) {
      builder = Health.up();
    } else if (!filesPresent || failure != null) {
      // ★ 明确"不正常但服务还活着"
      builder = Health.status("DEGRADED");
    } else {
      // 文件在、也没失败过、只是还没被用过 ⇒ 懒加载没触发。这不是故障。
      builder = Health.status("UNKNOWN");
    }

    builder
        .withDetail("mode", "local-onnx")
        .withDetail("ready", ready)
        .withDetail("model", local.servedModelName())
        .withDetail("modelPath", local.modelPath())
        .withDetail("modelFilesPresent", filesPresent);

    if (!ready && failure != null) {
      builder
          .withDetail("reason", failure)
          .withDetail(
              "hint",
              "按 models/README.md 校验模型文件的 size + sha256；★ 不会自动回退到上游，" + "两个向量空间混用会让检索照样有结果、只是全错");
    } else if (!ready && !filesPresent) {
      builder
          .withDetail("reason", "本地向量模型文件缺失")
          .withDetail("hint", "模型文件不进仓库（models/README.md §五 有复现步骤）；放好之前任何入库都会停在 PENDING_EMBEDDING");
    } else if (!ready) {
      builder.withDetail("reason", "尚未使用过（懒加载未触发）");
    }
    return builder.build();
  }
}
