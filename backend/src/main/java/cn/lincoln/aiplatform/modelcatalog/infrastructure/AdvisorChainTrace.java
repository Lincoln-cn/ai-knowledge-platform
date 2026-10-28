package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * advisor 链上的可观测探针（order 150），非流式与流式各一个计数。
 *
 * <p>★ 它存在的理由不是"加条日志"，而是让<b>链有没有真的走到</b>成为可核对的事实：{@code #62} 的判据③要求 "删掉 advisor 注册 ⇒
 * 某条断言必须变红"。没有这样一个计数器，删除注册后所有功能测试仍是绿的 —— 因为没人观察链。
 *
 * <p>★ 两个计数必须分开：流式与非流式走的是 {@link CallAdvisor} / {@link StreamAdvisor} 两套方法，
 * 一个"总共被走过几次"的计数会让"流式其实没挂上链"这件事在读数里看不出来（一侧的调用把另一侧的空洞盖住）。
 */
@Component
public class AdvisorChainTrace implements CallAdvisor, StreamAdvisor {

  private static final Logger log = LoggerFactory.getLogger(AdvisorChainTrace.class);

  private final AtomicLong calls = new AtomicLong();
  private final AtomicLong streams = new AtomicLong();

  @Override
  public ChatClientResponse adviseCall(
      ChatClientRequest request, CallAdvisorChain callAdvisorChain) {
    long seq = calls.incrementAndGet();
    if (log.isDebugEnabled()) {
      log.debug("advisor 链命中 #{}（order={}）", seq, getOrder());
    }
    return callAdvisorChain.nextCall(request);
  }

  /**
   * ★ 计数必须写在 {@link Flux#defer} 里，不能写在方法体里：{@code ChatClient} 装配时就会调到这里，
   * 写在方法体里等于"装配一次就记一次"，探针会变成为装配动作计数而不是为调用计数。
   */
  @Override
  public Flux<ChatClientResponse> adviseStream(
      ChatClientRequest request, StreamAdvisorChain streamAdvisorChain) {
    return Flux.defer(
        () -> {
          long seq = streams.incrementAndGet();
          if (log.isDebugEnabled()) {
            log.debug("advisor 链命中（stream）#{}（order={}）", seq, getOrder());
          }
          return streamAdvisorChain.nextStream(request);
        });
  }

  @Override
  public String getName() {
    return "advisor-chain-trace";
  }

  @Override
  public int getOrder() {
    return 150;
  }

  /** 当前进程内链被走过的次数（测试与排障用；不是业务指标）。 */
  public long callCount() {
    return calls.get();
  }

  /** 流式链被走过的次数（与非流式分开计，理由见类注释）。 */
  public long streamCount() {
    return streams.get();
  }
}
