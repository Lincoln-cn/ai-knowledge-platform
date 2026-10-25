package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.stereotype.Component;

/**
 * advisor 链上的可观测探针（order 150）。
 *
 * <p>★ 它存在的理由不是"加条日志"，而是让<b>链有没有真的走到</b>成为可核对的事实：{@code #62} 的判据③要求 "删掉 advisor 注册 ⇒
 * 某条断言必须变红"。没有这样一个计数器，删除注册后所有功能测试仍是绿的 —— 因为没人观察链。
 */
@Component
public class AdvisorChainTrace implements CallAdvisor {

  private static final Logger log = LoggerFactory.getLogger(AdvisorChainTrace.class);

  private final AtomicLong calls = new AtomicLong();

  @Override
  public ChatClientResponse adviseCall(
      ChatClientRequest request, CallAdvisorChain callAdvisorChain) {
    long seq = calls.incrementAndGet();
    if (log.isDebugEnabled()) {
      log.debug("advisor 链命中 #{}（order={}）", seq, getOrder());
    }
    return callAdvisorChain.nextCall(request);
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
}
