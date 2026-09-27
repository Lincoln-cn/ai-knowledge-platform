/**
 * 检索策略（retrieval）—— 向量 / 全文 / 混合三模式、参数校验、命中测试。
 *
 * <p>★ 三条硬约束落在这里： ① <strong>密级谓词必须两路同谓词</strong> —— 只给向量路加密级条件、漏掉全文路， 会形成"看着能过滤"的静默漏洞（{@code 13}
 * 五道闸门之二）； ② 配额合并用 {@code RrfQuotaJoiner}，<em>不是</em>把候选并起来取全局前 K（子问题保底配额）； ③ 向量路过滤受 pgvector
 * 后过滤影响，靠 {@code hnsw.iterative_scan} 与分区兜底（{@code 09 §九}、{@code 13}）。
 *
 * <p>★ 它<strong>不得依赖 {@code conversation}</strong> —— 这正是金丝雀样本要破坏的那条边： 检索是"被问答调用"的一侧，反向依赖意味着分层塌了。
 *
 * <p>依赖：{@code platform} · {@code modelcatalog} · {@code vectorindex} · {@code knowledge}。
 */
@ApplicationModule(allowedDependencies = {"platform", "modelcatalog", "vectorindex", "knowledge"})
package cn.lincoln.aiplatform.retrieval;

import org.springframework.modulith.ApplicationModule;
