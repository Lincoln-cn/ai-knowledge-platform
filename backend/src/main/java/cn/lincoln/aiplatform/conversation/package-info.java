/**
 * 会话与问答（conversation）—— 流式问答、引用、多轮、停止/重生成，以及引用分享的实时再滤。
 *
 * <p>★ <strong>不直接依赖 {@code knowledge}</strong>（{@code 01 §三.3} 的刻意取舍）：
 * 问答需要的是"命中分段的完整引用信息"（标题、内容、文档名、库名、排名）， 那正是 {@code retrieval} 的返回值 —— 少一个依赖，少一处耦合。
 *
 * <p>★ SSE 契约的四条时序由这里保证（{@code api/stream-types.ts} 权威）： {@code meta → refs → delta* → artifact →
 * done} · <strong>refs 必须先于第一个 delta</strong> · 任何终止路径（正常/停止/断开/超时）<strong>恰好一次 done</strong> · 心跳
 * {@code :keep-alive}（20s，60s 空闲主动结束）。流式下命中文档要从流元素 context 读， 不能等 {@code
 * ChatResponse.metadata}（{@code 11 §9}）。
 *
 * <p>依赖：{@code platform} · {@code identity} · {@code modelcatalog} · {@code retrieval} · {@code
 * assistant}。
 */
@ApplicationModule(
    allowedDependencies = {"platform", "identity", "modelcatalog", "retrieval", "assistant"})
package cn.lincoln.aiplatform.conversation;

import org.springframework.modulith.ApplicationModule;
