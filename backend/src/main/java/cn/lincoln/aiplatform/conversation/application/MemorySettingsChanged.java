package cn.lincoln.aiplatform.conversation.application;

/**
 * 记忆设定被改过（{@code 0.9.0 · #296 → #6}）。
 *
 * <h3>★ 为什么发事件，而不是直接调调度器</h3>
 *
 * <p>依赖方向是 {@code MemoryDreamScheduler → MemoryDreamJob → MemoryService} —— 做梦任务要读设定、
 * 也要调记忆服务重算画像。如果 {@link MemoryService} 反过来持有调度器，构造器注入就成环（Spring 启动即失败）。
 *
 * <p>★ 用"设定变了"这件事解耦而不是 {@code @Lazy}：调度器关心的不是"谁改的"，而是"库里现在的 cron
 * 是什么"。事件把这句话留给监听方自己读库，改设定的代码不必知道有哪些人在听。
 *
 * @param settings 落库后的新设定（只给"已经生效"的那一份，不给请求体）
 */
public record MemorySettingsChanged(MemorySettingsView settings) {}
