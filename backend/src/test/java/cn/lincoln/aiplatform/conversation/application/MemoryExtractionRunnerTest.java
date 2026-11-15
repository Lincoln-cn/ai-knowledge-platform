package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 记忆抽取必须是<strong>异步</strong>的（{@code 0.9.0 · #296}）。
 *
 * <h3>它守的是哪一种退化</h3>
 *
 * <p>抽取里有一次模型调用（超时 20 秒）和最多三次写库。把它放在聊天线程上，用户看不到延迟 —— 回答早就推过去了 ——
 * 但那条线程被按在那里。<strong>性能问题不会以"变慢"的形态出现，而是以 "并发一上来就排队"出现</strong>，而排队在单条请求的耗时读数里完全看不出来（{@code
 * silent-failure} 的一种：症状不在被观测的那条链上）。
 *
 * <p>★ 所以本类断的不是"抽取结果对不对"，而是"<strong>提交那一刻它没有在跑</strong>"。
 */
class MemoryExtractionRunnerTest {

  private static final UUID OWNER = UUID.randomUUID();
  private static final UUID CONVERSATION = UUID.randomUUID();
  private static final UUID MESSAGE = UUID.randomUUID();

  /** 判据：`submit` 在抽取跑完之前就返回，且返回值的完成要等抽取跑完。 */
  @Test
  void submitReturnsBeforeTheExtractionFinishes() {
    MemoryExtractor extractor = mock(MemoryExtractor.class);
    MemoryService memoryService = mock(MemoryService.class);
    when(extractor.extract(any(), any(), any(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(500);
              return List.of(draft("用户偏好先看结论"));
            });
    when(memoryService.write(any())).thenReturn(Optional.of(mock(Memory.class)));

    MemoryExtractionRunner runner = new MemoryExtractionRunner(extractor, memoryService);
    try {
      long started = System.nanoTime();
      var future = runner.submit(newTurn("问题", "回答"));
      long submitMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

      // ★ 500ms 的抽取不该让 submit 也等 500ms；留 300ms 余量给机器抖动
      assertThat(submitMs).as("submit 必须立刻返回（不能等抽取跑完）").isLessThan(300);
      assertThat(runner.inFlight()).as("提交后应有 1 个在跑").isEqualTo(1);

      assertThat(future.join()).as("抽取跑完后写入 1 条").isEqualTo(1);
      assertThat(runner.inFlight()).as("跑完后归零").isZero();
    } finally {
      runner.shutdown();
    }
  }

  /** 判据：抽取抛错 ⇒ future 给 0，不向上抛（旁路不该让这一轮问答失败）。 */
  @Test
  void extractionFailureYieldsZeroInsteadOfThrowing() {
    MemoryExtractor extractor = mock(MemoryExtractor.class);
    MemoryService memoryService = mock(MemoryService.class);
    when(extractor.extract(any(), any(), any(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("上游 500"));

    MemoryExtractionRunner runner = new MemoryExtractionRunner(extractor, memoryService);
    try {
      assertThat(runner.submit(newTurn("问题", "回答")).join()).isZero();
    } finally {
      runner.shutdown();
    }
  }

  /** 判据：回答为空 ⇒ 根本不提交（没有可提炼的东西，别白起一条线程）。 */
  @Test
  void blankAnswerDoesNotEvenSubmit() {
    MemoryExtractor extractor = mock(MemoryExtractor.class);
    MemoryService memoryService = mock(MemoryService.class);

    MemoryExtractionRunner runner = new MemoryExtractionRunner(extractor, memoryService);
    try {
      assertThat(runner.submit(newTurn("问题", "   ")).join()).isZero();
      assertThat(runner.inFlight()).isZero();
      verify(extractor, never()).extract(any(), any(), any(), anyString(), anyString());
    } finally {
      runner.shutdown();
    }
  }

  /** 判据：没有归属人 ⇒ 不提交（调试链路没有"谁的记忆"这回事）。 */
  @Test
  void turnWithoutOwnerDoesNotSubmit() {
    MemoryExtractor extractor = mock(MemoryExtractor.class);
    MemoryService memoryService = mock(MemoryService.class);

    MemoryExtractionRunner runner = new MemoryExtractionRunner(extractor, memoryService);
    try {
      var anonymous = new MemoryExtractionRunner.Turn(null, CONVERSATION, MESSAGE, "问题", "回答");
      assertThat(runner.submit(anonymous).join()).isZero();
      verify(extractor, never()).extract(any(), any(), any(), anyString(), anyString());
    } finally {
      runner.shutdown();
    }
  }

  private static MemoryExtractionRunner.Turn newTurn(String question, String answer) {
    return new MemoryExtractionRunner.Turn(OWNER, CONVERSATION, MESSAGE, question, answer);
  }

  private static MemoryService.MemoryDraft draft(String content) {
    return new MemoryService.MemoryDraft(
        OWNER,
        MemoryLevel.USER,
        null,
        MemoryKind.PREFERENCE,
        content,
        new UUID[] {MESSAGE},
        4,
        0.9f,
        "pref:" + content.hashCode());
  }
}
