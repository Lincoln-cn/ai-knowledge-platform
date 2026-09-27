/**
 * 文档加工链（ingestion）—— 解析 → 四层分块 → 向量化 → 状态回写，含真实进度与失败重试。
 *
 * <p>★ 它<strong>不认识</strong> {@code conversation}：加工是"对知识库做的事"，与谁在问无关。 进度靠按事实发布领域事件（{@code
 * EmbeddingProgressed}）而非内存计数器 —— 所以刷新页面不重置。
 *
 * <p>依赖：{@code platform} · {@code modelcatalog} · {@code vectorindex} · {@code knowledge}。 矩阵见
 * {@code 01 §三.2}。
 */
@ApplicationModule(allowedDependencies = {"platform", "modelcatalog", "vectorindex", "knowledge"})
package cn.lincoln.aiplatform.ingestion;

import org.springframework.modulith.ApplicationModule;
