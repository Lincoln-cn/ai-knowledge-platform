package cn.lincoln.aiplatform.modelcatalog.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 引用面读数端口（{@code #300} 的删除前置检查）。
 *
 * <p>★ 为什么是<strong>端口</strong>而不是在 {@code ModelService} 里直接 {@code JdbcTemplate} 查：
 * 引用分散在四个<strong>别的模块</strong>的表里 （{@code as_assistant} / {@code kb_knowledge_base} / {@code
 * kb_document} / {@code cv_chat_settings}）， 而 {@code modelcatalog} 的模块边界只允许依赖 {@code platform}（见
 * {@code package-info.java} 与 {@code ModuleBoundaryTest}）—— 不能 import 那些模块的实体或服务。
 *
 * <p>★ 也不能反过来让那些模块"注册引用"：那要求每个模块知道 {@code modelcatalog}， 而引用是<strong>使用方</strong>的事，方向会反过来。
 * 所以这里只留一个<strong>只读的跨表读数</strong>接口，实现放 infrastructure 直查 SQL —— 与 {@code
 * JdbcModelIdentityAdapter} 同一形状（同模块 infrastructure → application 方向合法）。
 *
 * <p>★ 端口只回答"谁在引用"，<strong>不负责拒绝</strong>：拒绝与 409 的措辞属于应用服务的判定， 这样"读数"才能被列表页（展示引用数）与删除路径共用。
 */
public interface ModelReferencePort {

  /**
   * 读某个模型被谁引用。
   *
   * @param modelId 模型 id
   * @return 引用面；没有引用时返回 {@link ModelReference#NONE}（不返回 null）
   */
  ModelReference ofModel(UUID modelId);

  /**
   * 读某个服务商被谁引用 —— 服务商自身不是被引用的对象，<strong>它的模型</strong>才是。
   *
   * <p>返回的 {@link ModelReference#models()} 是"挂在它下面的模型数"，其余四类是这些模型的引用面的<strong>并集</strong>
   * （强制删除服务商会连带删掉这些模型，所以必须把它们的下游一起摆出来）。
   *
   * @param providerId 服务商 id
   * @return 引用面；没有模型时 {@link ModelReference#NONE}
   */
  ModelReference ofProvider(UUID providerId);

  /**
   * 批量读多个模型的<strong>引用总数</strong>（列表页的「N 处引用」标签用）。
   *
   * <p>★ 一次查询取完，不做 N+1：列表一页 20 条模型，逐条查四次表就是 80 次往返。
   *
   * @param modelIds 模型 id 集合；空集合返回空 map
   * @return modelId → 引用总数；没有引用的模型<strong>不在</strong> map 里（调用方按 0 处理）
   */
  Map<UUID, Long> countsByModel(Collection<UUID> modelIds);

  /** 某个服务商下的模型（id + 名称），{@code limit} 条上限；给 {@link #ofProvider} 与强制删除用。 */
  List<ModelReference.Named> modelsOfProvider(UUID providerId, int limit);
}
