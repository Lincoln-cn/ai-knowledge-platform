package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.model.ModelEgressPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 出站闸门的实现（{@code #72}）—— 按 {@code ai_model.supplier} 定"这次能发多密"。
 *
 * <h3>★ 判据表（三条，各对应一个能红的断言）</h3>
 *
 * <table border="1">
 *   <caption>供应商 × 内容密级 ⇒ 是否允许进入本次上下文</caption>
 *   <tr><th>供应商</th><th>上限</th><th>理由</th></tr>
 *   <tr>
 *     <td>{@code LOCAL_INFERENCE}</td>
 *     <td>最高档（{@code CONFIDENTIAL}）</td>
 *     <td>★ <b>它不出网</b>：数据没有离开本机/本机房，密级体系管的是"谁能读"，
 *         而本地推理不制造新的数据流向 ⇒ 不该被这条闸门限制。
 *         ★ 这一行是<strong>防"一刀切禁用"</strong>的过度实现（{@code #72} 判据 3）。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code EXTERNAL_API}</td>
 *     <td>{@code app.egress.external-api-ceiling}（默认 {@code INTERNAL}）</td>
 *     <td>数据会离开边界 ⇒ 上限由配置决定，且默认值刻意保守。</td>
 *   </tr>
 *   <tr>
 *     <td>★ 取不到这个模型</td>
 *     <td>{@code null}（<strong>fail-closed</strong>）</td>
 *     <td>认不出来就一条都不给，不许当作"没有限制"。</td>
 *   </tr>
 * </table>
 *
 * <h3>★ 一个容易写错的地方（写下来防复发）</h3>
 *
 * <p>{@code supplier} 在库里是<strong>非空列</strong>（{@code V11} 的 {@code chk_ai_model_supplier}），
 * 所以这里<strong>不做 null 兜底</strong>：兜底成 {@code LOCAL_INFERENCE} 会让"一个没设供应商的模型" 被当成不出网 =
 * <strong>静默放宽</strong>。真的读到 null 是数据坏了，那时 fail-closed 更安全。
 */
@Component
public class ModelEgressAdapter implements ModelEgressPort {

  /** ★ 本地推理不出网 ⇒ 不设限。用最高档而不是"另加一个 boolean"：调用方只认"上限"这一个概念。 */
  private static final ClassificationLevel LOCAL_UNLIMITED = ClassificationLevel.CONFIDENTIAL;

  private final ModelRepository models;
  private final ModelUpstreamResolver upstream;
  private final EgressProperties properties;

  public ModelEgressAdapter(
      ModelRepository models, ModelUpstreamResolver upstream, EgressProperties properties) {
    this.models = models;
    this.upstream = upstream;
    this.properties = properties;
  }

  @Override
  public ClassificationLevel ceilingOf(UUID modelId) {
    if (modelId == null) {
      return null;
    }
    Model model = models.findById(modelId).orElse(null);
    if (model == null) {
      // ★ fail-closed：模型不存在时不许放行（调用方自己会把"null 上限"与"最高档"分开处置）
      return null;
    }
    // ★ 权威来源是服务商的 egress；服务商被停用/删除时空 ⇒ fail-closed，不回落到旧 supplier。
    return upstream.egressOf(model).map(egress -> ceilingFor(egress, properties)).orElse(null);
  }

  /**
   * 供应商 → 上限的那张判据表（★ 抽成静态纯函数，好让 {@code ModelEgressAdapterTest} 直接逐行守它）。
   *
   * <p>★ 为什么值得单独一个函数：这张表是 {@code #72} 的<strong>全部业务规则</strong>， 而它自己不需要数据库。混在 {@link #ceilingOf}
   * 里，测它就得造一个 {@code ModelRepository}， 于是最该被逐行核对的那三行反而最不容易被测到。
   *
   * @param supplier 库里的非空列（{@code V11} 的 {@code chk_ai_model_supplier}）
   * @param properties 平台配置
   * @return 上限；{@code null} = fail-closed（含"供应商取值不在枚举内"这种数据坏掉的情形）
   */
  public static ClassificationLevel ceilingFor(Supplier supplier, EgressProperties properties) {
    if (supplier == Supplier.LOCAL_INFERENCE) {
      // ★ 本地推理不出网 ⇒ 不设限。用最高档而不是"另加一个 boolean"：调用方只认"上限"这一个概念。
      return LOCAL_UNLIMITED;
    }
    if (supplier == Supplier.EXTERNAL_API) {
      return properties.externalApiCeiling();
    }
    // ★ 读到本枚举之外的供应商值（数据坏了、或将来加了新枚举值却忘了在这里处置）：
    //   同样 fail-closed，而不是挑一个"看起来安全"的默认分支 —— 那会让新增的枚举值**默认被放行**。
    return null;
  }

  /**
   * 服务商出网边界 → 上限（{@code #72} 的 provider-aware 版本）。
   *
   * <p>★ 与 {@link #ceilingFor(Supplier, EgressProperties)} 同一条判据表，只是入参从"旧 supplier"升级为 "服务商的
   * egress"：自建 vLLM 用 OpenAI 协议也必须是 LOCAL，不能按厂家名推断。
   */
  public static ClassificationLevel ceilingFor(ProviderEgress egress, EgressProperties properties) {
    if (egress == ProviderEgress.LOCAL) {
      return LOCAL_UNLIMITED;
    }
    if (egress == ProviderEgress.EXTERNAL) {
      return properties.externalApiCeiling();
    }
    return null;
  }
}
