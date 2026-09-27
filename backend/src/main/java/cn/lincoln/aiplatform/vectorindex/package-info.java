/**
 * 向量索引（vectorindex）—— 向量表读写、HNSW 索引维护、批量写入与进度回写。无对外端点。
 *
 * <p>★ 它是刻意"不懂业务"的基础设施（{@code 01 §三.3}）：只认识<em>向量 + 元数据 + 维度</em>， 不认识"知识库"。切分语义与归属都是调用方的事 ——
 * 这让它成为真正可替换的一层。
 *
 * <p>依赖：{@code platform}。矩阵见 {@code 01 §三.2}。
 */
@ApplicationModule(allowedDependencies = {"platform"})
package cn.lincoln.aiplatform.vectorindex;

import org.springframework.modulith.ApplicationModule;
