package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.application.ModelCapability;
import cn.lincoln.aiplatform.modelcatalog.application.ModelCapabilitySource;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 能力探测器 —— 元数据优先 + 行为探针兜底。
 *
 * <p>★ 四类能力的来源（{@code ROADMAP §4.7.3} 第 4 周测试项 ①）：
 *
 * <table>
 *   <tr><th>能力</th><th>方法</th></tr>
 *   <tr><td>contextWindow</td><td>元数据（{@code /v1/models} 的 {@code context_window} / {@code context_length}）</td></tr>
 *   <tr><td>tools</td><td>元数据（{@code /v1/models} 的 {@code tools} 字段）</td></tr>
 *   <tr><td>dimension</td><td>行为探针：调 embeddings，读返回向量长度</td></tr>
 *   <tr><td>thinking 三态</td><td>行为探针：带/不带 {@code reasoning_effort} 发 chat，看是否产出 reasoning</td></tr>
 * </table>
 *
 * <p>★ 思考三态的判定口径（与 {@code ModelCapabilityProbeTest} 同构）：
 *
 * <ul>
 *   <li>默认有 reasoning 且关思考后没有 ⇒ {@code TOGGLEABLE}
 *   <li>默认有 reasoning 且关思考后仍有 ⇒ {@code ALWAYS_ON}
 *   <li>默认就没有 reasoning ⇒ {@code NONE}
 * </ul>
 *
 * <p>★ 探测失败不抛到调用方的主流程 —— 由 {@link #probe} 的调用方捕获并转成可读错误， 用户可手工声明（{@code UpsertModelBody} 的 thinking
 * / tools / dimension）。
 */
@Component
public class CapabilityProber {

  private static final Logger log = LoggerFactory.getLogger(CapabilityProber.class);

  private final UpstreamGateway gateway;

  public CapabilityProber(UpstreamGateway gateway) {
    this.gateway = gateway;
  }

  /**
   * 探测一个基础模型的能力。
   *
   * @param apiBase 为 null 时用默认上游
   * @param apiKey 为 null 时用默认 key
   * @throws UpstreamGateway.UpstreamUnreachableException 上游完全不可达时（message 是可读原因）
   */
  public ModelCapability probe(String baseModel, ModelKind kind, String apiBase, String apiKey) {

    // ── 1. 元数据优先：从 /v1/models 拉能力元数据 ──
    Integer metaContextWindow = null;
    Integer metaDimension = null;
    String metaTools = null;
    List<UpstreamGateway.UpstreamModel> catalog = gateway.listModels(apiBase, apiKey);
    for (UpstreamGateway.UpstreamModel m : catalog) {
      if (baseModel.equals(m.id())) {
        metaContextWindow = m.contextWindow();
        metaDimension = m.dimension();
        metaTools = m.tools();
        break;
      }
    }

    if (kind == ModelKind.EMBEDDING) {
      // ── 2a. 向量模型：实测维度（元数据只是提示，必须实测）──
      int dimension = gateway.probeEmbeddingDimension(baseModel, apiBase, apiKey);
      return ModelCapability.of(
          ModelKind.EMBEDDING,
          ThinkingMode.NONE,
          ToolsMode.UNSUPPORTED,
          null,
          dimension,
          ModelCapabilitySource.PROBED);
    }

    // ── 2b. 对话模型：行为探针测思考三态 ──
    ThinkingMode thinking = probeThinking(baseModel, apiBase, apiKey);
    ToolsMode tools = parseTools(metaTools);
    return ModelCapability.of(
        ModelKind.CHAT, thinking, tools, metaContextWindow, null, ModelCapabilitySource.PROBED);
  }

  /**
   * 思考三态的行为探针。
   *
   * <p>★ 不看元数据的 thinking 字段 —— 三态的定义就是"行为上能不能关掉思考"， 只有发请求看响应才知道。元数据说是 ALWAYS_ON 但行为上关得掉，那是元数据在撒谎。
   */
  private ThinkingMode probeThinking(String baseModel, String apiBase, String apiKey) {
    String byDefault = gateway.chat(baseModel, null, apiBase, apiKey);
    String thinkingOff = gateway.chat(baseModel, "none", apiBase, apiKey);

    boolean sawDefault = UpstreamGateway.sawReasoning(byDefault);
    boolean sawOff = UpstreamGateway.sawReasoning(thinkingOff);

    if (!sawDefault) {
      return ThinkingMode.NONE;
    }
    if (sawOff) {
      return ThinkingMode.ALWAYS_ON;
    }
    return ThinkingMode.TOGGLEABLE;
  }

  /** 从元数据解析 tools 能力。解析不出来时保守取 {@code UNSUPPORTED}（不假装支持）。 */
  private static ToolsMode parseTools(String raw) {
    if (raw == null || raw.isBlank()) {
      return ToolsMode.UNSUPPORTED;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "PARALLEL" -> ToolsMode.PARALLEL;
      case "SUPPORTED" -> ToolsMode.SUPPORTED;
      default -> ToolsMode.UNSUPPORTED;
    };
  }
}
