package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.stream.StreamEvent;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

/**
 * SSE 响应体的<strong>唯一装配点</strong>（第 17 周 · 0.4.5，本批从 {@code ChatController} 抽出）。
 *
 * <h3>★★ 它为什么必须是一个独立的组件（抽出来的理由，不是"整理一下"）</h3>
 *
 * <p>公开面（免登录问答）要的是<strong>同一条链路换一个正当性入口</strong>，而"写帧"这件事 <strong>只允许有一处实现</strong>：开流填充帧、{@code
 * IOException → UncheckedIOException} 的包装、 安全上下文跨线程传播 —— 抄第二份的话，第二份不会跟着改，而症状是"公开面的流偶尔少一帧 / 断连后库里停在
 * GENERATING" 这类不会报错的偏差。
 *
 * <table>
 *   <tr><th>保证</th><th>载体</th></tr>
 *   <tr><td>帧的 wire 形状（{@code event:}/{@code data:}/注释帧）</td><td>{@link ChatStreamWriter}</td></tr>
 *   <tr><td>每帧立刻 flush / 断连可观测</td><td>本类的 {@link #streamOf}</td></tr>
 *   <tr><td>安全上下文跨线程</td><td>本类的 {@link #streamOf}（捕获 + 在异步线程上装上）</td></tr>
 *   <tr><td>事件顺序与恰好一次 {@code done}</td><td>{@code StreamAssembler}（经 {@link ChatService}）</td></tr>
 * </table>
 *
 * <h3>★★ 为什么用 {@link StreamingResponseBody} 而不是 {@code SseEmitter}</h3>
 *
 * <ol>
 *   <li><strong>每帧立刻 flush</strong>（{@code Writer#flush}）—— {@code SseEmitter} 也有，但它的 {@code send}
 *       会把事件重新拼一遍，"事件名从 {@code EventKind} 取"这条唯一性就多了一个绕行点；
 *   <li><strong>注释帧</strong>—— {@code SseEmitter} 只发 {@code event/data}，发注释要塞一个特殊对象再自己序列化，
 *       那等于把心跳的形状搬到控制器里；
 *   <li><strong>写失败能抛 {@code IOException}</strong>—— 这是 {@code DISCONNECT} 唯一的观测方式； {@code
 *       SseEmitter} 把写失败变成"完成回调"，调用方拿不到那个异常。
 * </ol>
 *
 * <h3>★★ 三个 {@link Supplier} 钩子的分工（这是本类能被两处复用的全部原因）</h3>
 *
 * <ol>
 *   <li>{@link AsyncTurn#authorize(Supplier)}（可选，默认"直接执行"）—— ★ 两个入口唯一的差别：
 *       <strong>正当性怎么证明</strong>（{@code 08 §4.4.4}）。公开面在这里调 {@code AccessScopePort#asSubject}
 *       把"链接那一份范围"装上。★ 它<strong>必须</strong>发生在异步线程上： 范围是 {@code ThreadLocal}，在请求线程上装、在异步线程上读，读到的是
 *       {@code nobody()} （检索安静地返 0 条， 看起来像"知识库里没有"）。
 *   <li>{@link AsyncTurn#beforeStream()} —— 正事开跑之前要做的落库（登录面 / 公开面都为空，两者都在
 *       <strong>请求线程</strong>上先把消息落好，这样"先建立连接"与"错误仍走包络"两件事同时成立）；
 *   <li>{@link #streamOf} 自己 —— 开流填充帧 → {@link ChatService#streamTurn} 逐帧写出去。
 * </ol>
 */
@Component
public class ChatStreamAssembler {

  /**
   * 开流时那条"填充"注释帧的载荷 —— ★ 它必须<strong>足够长</strong>，长到能让容器把 HTTP 响应头提交出去。
   *
   * <p>2KB 是本机实测的可用量级（Tomcat 默认的响应缓冲是 8KB，但它对"已经 flush 过一次"的响应会立刻提交头部）。 ★ 它的前 6 个字符是 {@code open:
   * }：注释帧的内容对前端没有语义，但一个可读的前缀能让 "抓包看到的第一条注释帧"自己说明来意（而不是一串看起来像乱码的填充）。
   */
  // ★★ 12KB，不是 2KB（实测：2KB 不够，原因是 Tomcat 的响应缓冲是 8KB）
  //
  //   症状（本轮实测）：后端确实按 400ms/块回放（日志「911 字 → 38 块 … 预计回放 14.8s」、
  //   总时长 17.6s 也对得上），但**delta 全部在同一毫秒到达客户端** —— 因为
  //   `: open`(2KB) + 正文(3.2KB) = 5.2KB **小于 8KB 缓冲** ⇒ 一直攒着，直到流结束才一起发。
  //
  //   ⇒ 首帧必须自己把缓冲写满、逼容器提交响应头。提交之后，chunked 模式下每次 flush
  //     都会真的发出一个 chunk（那时 400ms/块才看得见）。
  //   ★ 12KB = 8KB 缓冲 + 余量；它只在每条流开头发一次，占用的带宽可以忽略。
  private static final String OPEN_PADDING =
      "open: " + "sse-response-header-commit-padding.".repeat(480);

  private static final Logger log = LoggerFactory.getLogger(ChatStreamAssembler.class);

  private final ChatService chatService;
  private final ChatStreamWriter writer;

  public ChatStreamAssembler(ChatService chatService, ObjectMapper json) {
    this.chatService = chatService;
    this.writer = new ChatStreamWriter(json);
  }

  /**
   * 一个异步入口要用到的三个钩子。
   *
   * @param turn 这一轮（{@code meta} 的 messageId 由它给出）
   * @param question 用户提问（进提示词）
   * @param beforeStream 开流填充帧之后、{@link ChatService#streamTurn} 之前要做的事（可空 = 什么都不做）
   * @param authorize ★ 正当性钩子（见 {@link Authorizer}）；可空 = 直接执行
   */
  public record AsyncTurn(
      ChatTurnWriter.PreparedTurn turn,
      String question,
      Runnable beforeStream,
      Authorizer authorize) {

    /**
     * 只给"这一轮怎么跑"的入口（登录面用它）。
     *
     * <p>★ 默认 {@code authorize} 是<strong>空</strong>而不是"不做任何事"的一个 lambda：让"没人包装"这件事在 {@link
     * #streamOf} 里被显式读出来，而不是藏在一个恒等函数里。
     */
    public AsyncTurn(ChatTurnWriter.PreparedTurn turn, String question) {
      this(turn, question, null, null);
    }
  }

  /**
   * 正当性包装器 —— 收下"要跑的取数逻辑"，在<strong>正确的正当性上下文里</strong>执行它。
   *
   * <p>★ 定义成钩子而不是"一个 {@code ScopeFilter} 参数"：本类<strong>不</strong>认识范围、 也<strong>不</strong>许自己往
   * {@code ThreadLocal} 里塞东西 —— 那是 {@code AccessScopeAdapter} 里 {@code asSubject} / {@code asScope}
   * 唯一的那一处实现（{@code 08 §4.4.4} 纪律 4）。 本类只保证"这段逻辑跑在异步线程上、 安全上下文已经装上"。
   */
  @FunctionalInterface
  public interface Authorizer {

    /**
     * @param body 真正要跑的取数逻辑（执行它 = 跑完这一轮问答）
     */
    void authorize(Runnable body);
  }

  /**
   * 把这一轮问答装配成一个 SSE 响应体（★ 真正的工作在 MVC 的<strong>异步线程</strong>上发生）。
   *
   * <p>★ 顺序是刻意的：<strong>先写开流填充帧，再跑取数与生成</strong>。 填充帧的用途是"把 HTTP 响应头提交出去"，
   * 它必须在任何可能耗时的事情之前发生，否则客户端在整条流结束之前读不到任何一帧（见 {@link #writeOpenPadding}）。
   *
   * @return SSE 响应体；媒体类型由调用方在 {@code ResponseEntity} 上<strong>显式</strong>给出
   */
  public StreamingResponseBody streamOf(AsyncTurn asyncTurn) {
    return streamOf(asyncTurn, null);
  }

  /**
   * ★★ 带 {@code HttpServletResponse} 的版本：每一帧写完调 {@code flushBuffer()}。
   *
   * <h3>为什么必须这么干（实测证据）</h3>
   *
   * <p>只用 {@code Writer.flush()} 时，原始 socket 抓到的 TCP chunk <b>全是 8192 字节</b> ——正好等于 Tomcat 的 {@code
   * OutputBuffer} 容量。那说明： <b>容器只有在缓冲写满时才发 chunk，{@code Writer.flush()} 并不触发它</b>。
   *
   * <p>后果就是用户看到的「首字很快、然后一堆 token 一起蹦出来」： 88 个 delta 里 81 个被攒进同一个 8KB 缓冲，最后一次性发出。
   *
   * <p>{@code HttpServletResponse.flushBuffer()} 是唯一能"逼容器现在就把已写入的字节发出去" 的接口；{@code
   * StreamingResponseBody} 只给 {@code OutputStream}，所以必须从控制器把 response 传进来。
   *
   * @param response 可空（测试/无 web 上下文时退化为只 flush Writer）
   */
  public StreamingResponseBody streamOf(
      AsyncTurn asyncTurn, jakarta.servlet.http.HttpServletResponse response) {
    // ★ 在**当前（请求）线程**上捕获安全上下文：MVC 的异步执行在另一条线程上，
    //   而 SecurityContextHolder 默认是 ThreadLocal（项目没开它的策略配置）。
    //   ★ 不传播的症状极具误导性：检索侧的密级谓词读 currentScope() ⇒ 拿不到身份 ⇒
    //     AccessScopeAdapter 返回 nobody() ⇒ 检索安静地返 0 条（看起来像"知识库里没有"）。
    SecurityContext context = SecurityContextHolder.getContext();
    Runnable beforeStream = asyncTurn.beforeStream() == null ? () -> {} : asyncTurn.beforeStream();
    Authorizer authorize = asyncTurn.authorize();
    return outputStream -> {
      SecurityContext previous = SecurityContextHolder.getContext();
      SecurityContextHolder.setContext(context);
      try (Writer out = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
        writeOpenPadding(out);
        Runnable turn =
            () -> {
              beforeStream.run();
              chatService.streamTurn(
                  asyncTurn.turn(),
                  asyncTurn.question(),
                  event -> {
                    write(event, out);
                    flushContainer(response);
                  });
            };
        if (authorize == null) {
          turn.run();
        } else {
          authorize.authorize(turn);
        }
      } catch (Error fatal) {
        // ★ Error 一律继续抛：真出 OOM / StackOverflow 时「安静收尾」比崩溃更难查。
        throw fatal;
      } catch (Throwable failure) {
        // ★★ 这个 catch 的存在理由只有一个：**让原始异常的类型进日志**。
        //   响应一旦提交（开流填充帧就是为了提交它），异常就再也走不到包络那条路 ——
        //   容器会在「已提交的响应上再写一遍错误」时抛它自己的 NPE（#216 的现场：
        //   MimeHeaders.removeHeader / this.headers[i] is null），原始异常被这层二次崩溃吃掉，
        //   日志里只剩「Exception while processing an asynchronous request」，类型完全取不到。
        //   ⇒ 已提交：记下类型 + 栈，然后**不再抛**（客户端已经拿到 200 与半条流，再抛只会再炸一次）；
        //   ⇒ 未提交：记下类型 + 栈，再原样抛出（包络那条路仍然是唯一的错误形状）。
        boolean committed = response != null && response.isCommitted();
        logFailure(failure, committed);
        if (!committed) {
          throw asUnchecked(failure);
        }
      } finally {
        SecurityContextHolder.setContext(previous);
      }
    };
  }

  /** 把「流为什么异常收尾」写进日志：类型 + 消息 + 栈，并带上响应是否已提交。 */
  private static void logFailure(Throwable failure, boolean committed) {
    if (failure instanceof UncheckedIOException disconnected) {
      // 客户端断开是**预期事件**（DISCONNECT 已经有了领域事件与消息状态），不该按 ERROR 记账。
      // ★ 用 String.valueOf 而不是直接传 cause：slf4j 会把「最后一个参数是 Throwable」当作异常吃掉，
      //   占位符就留在日志里（本类第一版就是这么错的，读数里出现过字面量 `：{}`）。
      log.debug("SSE 流因写失败收尾（响应已提交={}）：{}", committed, String.valueOf(disconnected.getCause()));
      return;
    }
    log.error(
        "SSE 流异常收尾（响应已提交={}）：原始异常类型 = {}，消息 = {}",
        committed,
        failure.getClass().getName(),
        failure.getMessage(),
        failure);
  }

  /** 未提交时的重抛：checked 异常裹成 {@link UncheckedIOException}，保持「DISCONNECT 只有一种形状」。 */
  private static RuntimeException asUnchecked(Throwable failure) {
    if (failure instanceof RuntimeException runtime) {
      return runtime;
    }
    if (failure instanceof IOException io) {
      return ChatStreamWriter.disconnected(io);
    }
    return new IllegalStateException(failure);
  }

  /**
   * 开流先写一条 SSE <strong>注释帧</strong>（{@code : open} + 2KB 填充）并立刻 flush（本批实测踩到过）。
   *
   * <p>{@code StreamingResponseBody} 的第一个字节写出去<strong>不等于</strong> HTTP 响应头已经提交：
   * 容器会把头部攒在缓冲区里，直到它觉得"差不多了"（数据够多 / 缓冲区满 / 响应结束）。 于是客户端在<strong>整条流结束之前</strong>一个字节都读不到 —— 第一帧
   * {@code meta} 也要等 10 秒才出现。
   *
   * <p>症状极具误导性：前端"生成中停止"的按钮要等 {@code meta} 拿到 {@code messageId} 才可用， 而 {@code meta} 要等整篇答案写完才到 ⇒
   * <strong>停止按钮永远点不了</strong>， 而界面看起来只是"有点慢"。★ 本批的停止用例就是这么发现的： 客户端置停止信号的那一刻，服务端早就写完了。
   *
   * <p>⇒ ① 它<strong>不是</strong>业务事件（前端解析器跳过，不影响事件序列）； ② 2KB 足够让容器把响应头提交出去（本机实测：提交之后逐帧都是实时的）。
   * 心跳同样是注释帧，所以这一步不引入任何新形状。
   */
  private void writeOpenPadding(Writer out) {
    try {
      writer.writeComment(OPEN_PADDING, out);
    } catch (IOException failure) {
      // ★ 第一帧就写不出去 ⇒ 与后续每一帧同一条路径（DISCONNECT 的观测点只有一个形状）
      throw ChatStreamWriter.disconnected(failure);
    }
  }

  /** 把一条领域事件写成 wire 帧；写失败以 {@link UncheckedIOException} 传出（{@code DISCONNECT} 的唯一依据）。 */
  /**
   * 逼容器把已写入的字节立刻发出去（chunked 模式下每调用一次就发一个 chunk）。
   *
   * <p>★ 失败不抛：客户端已断开时这里会抛 {@code IOException}，而那个信息 由 {@code write(...)} 的同一条路径已经报过（{@code
   * DISCONNECT} 的唯一观测点）， 在这里再抛一次只会变成"同一件事报两遍"。
   *
   * @param response 可空
   */
  private static void flushContainer(jakarta.servlet.http.HttpServletResponse response) {
    if (response == null) {
      return;
    }
    try {
      response.flushBuffer();
    } catch (java.io.IOException disconnected) {
      // 交给下一次 write(...) 以 UncheckedIOException 的形式统一上报
    }
  }

  private int write(StreamEvent event, Writer out) {
    try {
      return writer.write(event, out);
    } catch (IOException failure) {
      throw ChatStreamWriter.disconnected(failure);
    }
  }
}
