package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.WorkflowSearchPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 检索节点（{@code SEARCH_DATASET_NODE}）的执行器（{@code #326}）。
 *
 * <p>★ 它在 {@code #326} 之前一直不存在，而发布校验却把 {@code SEARCH_DATASET_NODE} 当作"当前能跑" —— 结果是发布放行、点试用时报「没有类型
 * SEARCH_DATASET_NODE 的节点执行器」。
 *
 * <p>★ 它读两样配置（都在节点的 {@code config} 里）：
 *
 * <ul>
 *   <li>{@link #CONFIG_KB_IDS}（{@code kbIds}）：要检索哪些知识库；旧画布名 {@link #LEGACY_KB_IDS} 作回退
 *   <li>{@code retrieval.topN}：取前几条（缺省 5）
 * </ul>
 *
 * <p>★ <strong>没有知识库时抛异常而不是静默写空串</strong>：一个配好了但没绑库的检索节点，跑出来的必然是"什么都查不到"； 若写空串进 {@code state}，下游
 * {@code AI_CHAT_NODE} 会把它当成"有一份空资料"照常作答，模型对着空资料编答案 —— 用户看到的是"回答得很笃定但完全不对"。明确失败至少能让失败点落在配置上。
 *
 * <p>★ <strong>端口缺失时也抛</strong>：用 {@link ObjectProvider} 而不是构造器直接注入，是为了区分"没装配实现"与
 * "装配了但检索没命中"。后者是正常结果（空列表），前者是部署问题，不该长得一样。
 */
@Component
public class SearchDatasetNodeExecutor implements NodeExecutor {

  /** 检索结果写进 state 的键。 */
  public static final String RESULT_KEY = WorkflowNodeKeys.Output.RETRIEVAL_RESULT;

  /**
   * 结构化命中（JSON 数组）写进 state 的键 —— 给下游重排节点用。
   *
   * <p>★ 为什么不复用 {@link #RESULT_KEY}：那个键是**给模型看的拼接文本**，（{@code [文档名] 正文}，段与段之间空行）， 拼接之后 id 就没了。而重排要按
   * id 排序（{@code RerankPort} 的契约是收 id 列表返回 id 排列）， 从一段文本里反解 id 等于把"检索侧给的事实"再猜一遍。两个键，两种用途，各写一份。
   *
   * <p>★ 每项形状：{@code {"segmentId": "...", "docName": "...", "content": "..."}}。
   */
  public static final String HITS_KEY = WorkflowNodeKeys.Output.RETRIEVAL_HITS;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 配置里读知识库列表的键：与前端契约 {@code SearchDatasetNodeConfig.kbIds} 同名。 */
  static final String CONFIG_KB_IDS = WorkflowNodeKeys.Config.KB_IDS;

  /** 旧画布形状的键，只作回退（老草稿里存的是这个）。 */
  static final String LEGACY_KB_IDS = WorkflowNodeKeys.Legacy.DATASET_ID_LIST;

  private static final int DEFAULT_TOP_N = 5;

  private final ObjectProvider<WorkflowSearchPort> searchPort;

  public SearchDatasetNodeExecutor(ObjectProvider<WorkflowSearchPort> searchPort) {
    this.searchPort = searchPort;
  }

  @Override
  public String type() {
    return "SEARCH_DATASET_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    List<String> kbIds = kbIds(context.nodeConfig());
    if (kbIds.isEmpty()) {
      throw new IllegalStateException("检索节点没有绑定任何知识库，先选库再试");
    }
    WorkflowSearchPort port = searchPort.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("检索能力还没装配：没有 WorkflowSearchPort 的实现");
    }
    String query = context.input() == null ? "" : context.input();
    int topN = topN(context.nodeConfig());

    StringBuilder merged = new StringBuilder();
    List<Map<String, Object>> hits = new ArrayList<>();
    for (String raw : kbIds) {
      UUID kbId;
      try {
        kbId = UUID.fromString(raw);
      } catch (IllegalArgumentException ex) {
        throw new IllegalStateException("检索节点的知识库 id 不是合法 UUID：" + raw, ex);
      }
      List<WorkflowSearchPort.Snippet> snippets = port.search(kbId, query, topN);
      // ★ 端口返回 null 视为实现违约，而不是"没命中"：契约写的是空列表。
      if (snippets == null) {
        throw new IllegalStateException("检索端口返回了 null，契约要求空列表");
      }
      for (WorkflowSearchPort.Snippet snippet : snippets) {
        if (!merged.isEmpty()) {
          merged.append("\n\n");
        }
        merged.append('[').append(snippet.docName()).append("] ").append(snippet.content());
        // 同一次遍历里产出两份：给模型的文本、给重排的 id + 正文。两处各写一遍会漂。
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("segmentId", snippet.segmentId() == null ? null : snippet.segmentId().toString());
        hit.put("docName", snippet.docName());
        hit.put("content", snippet.content());
        hits.add(hit);
      }
    }
    context.state().write(RESULT_KEY, merged.toString(), KeyUpdate.REPLACE);
    context.state().write(HITS_KEY, MAPPER.writeValueAsString(hits), KeyUpdate.REPLACE);
  }

  /**
   * 要检索的知识库列表；缺失或类型不对给空列表（由调用方决定报错）。
   *
   * <p>★ <strong>先读契约名 {@code kbIds}</strong>（{@code #335}）：前端 {@code graph-format.ts} 的 {@code
   * searchFromLegacy} 发出的就是 {@code kbIds}，而 {@code NodeConfigReader} 也按 {@code kbIds} 做执行准入 ——
   * 只有本执行器读旧画布名 {@code dataset_id_list}，于是出现"准入说可以跑、执行器说没绑库"。旧名保留为回退读取， 因为库里存过的老草稿是那个形状。
   */
  private static List<String> kbIds(Map<String, Object> config) {
    Object raw = config == null ? null : config.get(CONFIG_KB_IDS);
    if (!(raw instanceof List<?>) && config != null) {
      raw = config.get(LEGACY_KB_IDS);
    }
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> ids = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item instanceof String text && !text.isBlank()) {
        ids.add(text);
      }
    }
    return ids;
  }

  /** 取前几条；缺失或不是正整数时给 {@link #DEFAULT_TOP_N}。 */
  private static int topN(Map<String, Object> config) {
    Object retrieval = config == null ? null : config.get(WorkflowNodeKeys.Config.RETRIEVAL);
    if (retrieval instanceof Map<?, ?> map) {
      Object topN = map.get(WorkflowNodeKeys.Config.TOP_N);
      if (topN instanceof Number number && number.intValue() > 0) {
        return number.intValue();
      }
    }
    return DEFAULT_TOP_N;
  }
}
