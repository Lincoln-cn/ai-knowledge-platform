package cn.lincoln.aiplatform.retrieval.infrastructure;

import cn.lincoln.aiplatform.platform.assistant.WorkflowSearchPort;
import cn.lincoln.aiplatform.retrieval.application.HitTestQuery;
import cn.lincoln.aiplatform.retrieval.application.HitTestView;
import cn.lincoln.aiplatform.retrieval.application.RetrievalService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 「检索节点」的检索能力：把工作流的检索请求转给 {@link RetrievalService}。
 *
 * <p>★ 为什么在此之前一直是空的：{@code SearchDatasetNodeExecutor} 只认 {@link WorkflowSearchPort}， 而全仓没有一个实现类 ——
 * 于是那条节点在真实运行里必然抛「检索能力还没装配」， 单测全绿也掩盖不了（`#334`）。这个类的唯一职责就是让那条路真的通。
 *
 * <p>★ 为什么实现放在 retrieval 模块：端口在 {@code platform}（跨模块 SPI 放 platform）， 实现放<strong>提供方</strong>模块 ——
 * assistant 的 allowedDependencies 里没有 retrieval， 它也不该为了检索去认识 retrieval 的内部类型。
 *
 * <p>★ 为什么走 {@link RetrievalService#hitTest} 而不是自己拼 SQL：命中测试那条路已经带着
 * 「密级上限、组织范围、三模式融合、阈值」这些口径；工作流检索要是另走一条，就会出现 「界面上搜到的」与「流程里搜到的」不一样，而两边都不报错。
 *
 * <p>★ 阈值这里不传（取命中的默认值）：端口 {@link WorkflowSearchPort#search} 的签名里没有阈值， 这是端口的欠账而不是本类的选择 —— 节点的 {@code
 * retrieval.threshold} 目前对工作流检索不生效， 已在 `#334` 里写明。要修就得扩端口，那是一次契约变更，不放进「补一个实现」这件事里。
 */
@Component
public class WorkflowSearchAdapter implements WorkflowSearchPort {

  /** 检索模式：混合（向量 + 全文，RRF 融合）。节点侧今天没有模式选项，默认给最有用的那个。 */
  private static final String DEFAULT_MODE = "HYBRID";

  private final RetrievalService retrieval;

  public WorkflowSearchAdapter(RetrievalService retrieval) {
    this.retrieval = retrieval;
  }

  @Override
  public List<Snippet> search(UUID kbId, String query, int topN) {
    HitTestView view =
        retrieval.hitTest(
            new HitTestQuery(
                DEFAULT_MODE,
                null, // 阈值用命中默认值（见类注释：端口没有这个参数）
                topN,
                null,
                List.of(kbId),
                query,
                null,
                null,
                null,
                null,
                null,
                null));
    return view.items().stream()
        .map(item -> new Snippet(item.segmentId(), item.docName(), item.content()))
        .toList();
  }
}
