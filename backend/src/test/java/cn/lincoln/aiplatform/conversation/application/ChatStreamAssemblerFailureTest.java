package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * SSE 流「异常收尾」的读数（`#216`）—— 用户可见的那一半没错，错的是**事后无据可查**。
 *
 * <h3>它测的是什么</h3>
 *
 * <p>开流填充帧会先把响应头提交出去（否则首帧要等整条流结束）。提交之后，异常就**再也走不到错误包络**那条路了 —— 容器会在「已经提交的响应上再写一遍错误」时抛它自己的
 * NPE（`#216` 的现场：`MimeHeaders.removeHeader` 的 `this.headers[i] is
 * null`），原始异常被这层二次崩溃吃掉，日志里只剩一句「Exception while processing an asynchronous request」，**类型完全取不到**。
 *
 * <p>所以本类断的是一件很窄、但当时确实没有的事：<strong>异常是什么类型，必须留在日志里</strong>。
 *
 * <h3>三条判据与它们的反证</h3>
 *
 * <ol>
 *   <li>响应已提交（真实流的中途）⇒ 异常<strong>不再往外抛</strong>（再抛只会让容器再炸一次），但日志里必须出现 <strong>原始类型名与消息</strong>。★
 *       反证：把 {@code ChatStreamAssembler.streamOf} 里那个 {@code catch (Throwable)}
 *       去掉，本用例立刻红在「什么都没记下来」。
 *   <li>响应未提交（一个字节都没出去）⇒ 异常<strong>原样抛出</strong>，让包络那条路继续做它该做的事。★ 反证：把 {@code if (!committed)}
 *       改成无条件 {@code return}，本用例红在「异常被吞了」。
 *   <li>客户端断开（写失败）是<strong>预期事件</strong> ⇒ 只记 debug，不按 ERROR 记账。★ 反证：把那条 {@code instanceof
 *       UncheckedIOException} 分支删掉，本用例红在「断开被记成错误」。
 * </ol>
 */
class ChatStreamAssemblerFailureTest {

  /** 只作为 `streamTurn` 的一个入参：本轮内容对「异常怎么收尾」没有影响，所以字段全空。 */
  private static final ChatTurnWriter.PreparedTurn TURN =
      new ChatTurnWriter.PreparedTurn(
          null, null, null, "trace-1", "stub", null, List.of(), false, false, null, null, List.of(),
          null);

  private final ChatService chatService = mock(ChatService.class);
  private final ChatStreamAssembler assembler =
      new ChatStreamAssembler(chatService, new ObjectMapper());

  private Logger assemblerLogger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void attachAppender() {
    assemblerLogger = (Logger) LoggerFactory.getLogger(ChatStreamAssembler.class);
    captured = new ListAppender<>();
    captured.start();
    assemblerLogger.addAppender(captured);
    assemblerLogger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detachAppender() {
    assemblerLogger.detachAppender(captured);
    captured.stop();
  }

  @Test
  @DisplayName("响应已提交之后炸了：日志里有原始类型与消息，异常不再往外抛")
  void aFailureAfterTheResponseIsCommittedIsLoggedWithItsOwnTypeAndStopsThere() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);
    doThrow(new IllegalStateException("上游在流中途炸了"))
        .when(chatService)
        .streamTurn(any(), any(), any());

    assertThatCode(() -> stream(response)).doesNotThrowAnyException();

    ILoggingEvent failure = lastError();
    assertThat(failure).as("已提交时的异常必须留下一条 ERROR").isNotNull();
    assertThat(failure.getFormattedMessage()).contains("响应已提交=true").contains("上游在流中途炸了");
    assertThat(failure.getThrowableProxy()).isNotNull();
    assertThat(failure.getThrowableProxy().getClassName())
        .as("★ 这一句就是 #216 要的东西：原始异常的类型")
        .isEqualTo("java.lang.IllegalStateException");
  }

  @Test
  @DisplayName("响应还没提交就炸了：异常原样抛出，包络那条路不被抢走")
  void aFailureBeforeAnythingIsCommittedStillReachesTheCaller() {
    // ★ 为什么不用 MockHttpServletResponse：它**写第一个字节就置 committed**，
    //   而"未提交"这条分支的前提恰恰是「一个字节都没出去」（真机上只可能是"第一帧就写失败"）。
    //   所以这里用替身把 committed 那个读数按住不动。
    HttpServletResponse response = neverCommitted();
    doThrow(new IllegalStateException("开流之前就炸了")).when(chatService).streamTurn(any(), any(), any());

    assertThatThrownBy(() -> stream(response))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("开流之前就炸了");

    assertThat(lastError()).as("未提交也要记一笔：包络只给用户看，排查只有这条日志").isNotNull();
  }

  @Test
  @DisplayName("客户端断开（写失败）按 debug 记账，不按 ERROR —— 那是预期事件")
  void aDisconnectIsNotCountedAsAnError() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);
    doThrow(new UncheckedIOException(new IOException("Broken pipe")))
        .when(chatService)
        .streamTurn(any(), any(), any());

    assertThatCode(() -> stream(response)).doesNotThrowAnyException();

    assertThat(lastError()).as("断开不该按错误记账（否则线上全是噪声，真错误被埋掉）").isNull();
    assertThat(captured.list)
        .anySatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains("SSE 流因写失败收尾")
                    .contains("Broken pipe"));
  }

  private void stream(HttpServletResponse response) throws IOException {
    assembler
        .streamOf(new ChatStreamAssembler.AsyncTurn(TURN, "问题"), response)
        .writeTo(response.getOutputStream());
  }

  /** 一个「写得出字节、但 committed 恒 false」的响应替身。 */
  private static HttpServletResponse neverCommitted() {
    HttpServletResponse response = mock(HttpServletResponse.class);
    ServletOutputStream sink =
        new ServletOutputStream() {
          private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

          @Override
          public void write(int value) {
            bytes.write(value);
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener listener) {
            // 同步写，没有异步监听要注册
          }
        };
    try {
      doReturn(sink).when(response).getOutputStream();
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
    doReturn(false).when(response).isCommitted();
    return response;
  }

  private ILoggingEvent lastError() {
    return captured.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .reduce((first, second) -> second)
        .orElse(null);
  }
}
