package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.EgressProperties;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelEgressAdapter;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code #72} 的判据表：<strong>供应商 → 允许处理的内容密级上限</strong>。
 *
 * <h3>为什么这三行必须逐行被守</h3>
 *
 * <p>这张表就是 {@code #72} 的<strong>全部业务规则</strong>。而它的三行各自对应一种 完全不同的错误，读数却能一样（都是"问答照常工作"）：
 *
 * <table border="1">
 *   <caption>写错任何一行会发生什么</caption>
 *   <tr><th>行</th><th>写错的后果</th></tr>
 *   <tr>
 *     <td>{@code LOCAL_INFERENCE → 最高档}</td>
 *     <td>★ 写成"也受配置限制" ⇒ 本地模型被无谓禁用 ⇒ <strong>一刀切过度实现</strong>
 *         （{@code #72} 判据 3 专门要防的那个）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code EXTERNAL_API → 配置值}</td>
 *     <td>★ 写成常量最高档 ⇒ <strong>闸门形同虚设</strong>（最严重）</td>
 *   </tr>
 *   <tr>
 *     <td>未知供应商 {@code → null}</td>
 *     <td>★ 写成"默认放行" ⇒ 将来新增一个供应商枚举值时，它<strong>默认被放行</strong>；
 *         这是"新增枚举值忘了处置"能悄悄发生的地方</td>
 *   </tr>
 * </table>
 *
 * <p>★ 它们<strong>不是</strong>同一件事，所以必须分成三条断言 —— 一条断言过不了任何一种混淆。
 */
class ModelEgressAdapterTest {

  private static final ClassificationLevel CONFIGURED = ClassificationLevel.INTERNAL;

  private static EgressProperties props() {
    return new EgressProperties(CONFIGURED);
  }

  @Test
  void localInferenceIsNotLimitedByTheEgressCeiling() {
    // ★ 本地推理不出网：数据没有离开本机 ⇒ 密级体系管"谁能读"，这里不该再加一道限制。
    //   ★ 这一条就是 #72 判据 3 的"防一刀切禁用"。
    assertThat(ModelEgressAdapter.ceilingFor(Supplier.LOCAL_INFERENCE, props()))
        .as("★ LOCAL_INFERENCE 必须拿到最高档上限（不是配置值）—— 否则本地模型会被无谓禁用")
        .isEqualTo(ClassificationLevel.CONFIDENTIAL);
  }

  @Test
  void externalApiUsesTheConfiguredCeiling() {
    // ★ 这一条是闸门的本体：外部 API 的上限**必须**来自配置，而不是常量。
    assertThat(ModelEgressAdapter.ceilingFor(Supplier.EXTERNAL_API, props()))
        .as("★ EXTERNAL_API 必须用配置里的上限 —— 写成常量最高档就是闸门形同虚设")
        .isEqualTo(CONFIGURED);
  }

  @Test
  void configuredCeilingIsActuallyRead() {
    // ★ 上一条只证明"等于 CONFIGURED"，而 CONFIGURED 若恰好等于 LOCAL 那一档就分辨不出。
    //   换一个**不同的**配置值再问一次，才能证明"配置真的被读到"，而不是返回了一个巧合同值的常量。
    EgressProperties stricter = new EgressProperties(ClassificationLevel.PUBLIC);
    assertThat(ModelEgressAdapter.ceilingFor(Supplier.EXTERNAL_API, stricter))
        .as("★ 配置换成 PUBLIC ⇒ 外部模型的上限必须跟着变（证明配置真的被读，而不是被忽略）")
        .isEqualTo(ClassificationLevel.PUBLIC);
  }

  @Test
  void nullSupplierFailsClosedInsteadOfBeingTreatedAsLocal() {
    // ★ 最危险的一个方向：把"没设供应商"当成 LOCAL_INFERENCE ⇒ **静默放宽**。
    //   库列是非空的（V11 的 chk_ai_model_supplier），真读到 null 就是数据坏了，
    //   那时 fail-closed 比"猜一个安全的值"安全得多。
    assertThat(ModelEgressAdapter.ceilingFor(null, props()))
        .as("★ 未知/空供应商必须返回 null（fail-closed），不许被当作本地推理放行")
        .isNull();
  }
}
