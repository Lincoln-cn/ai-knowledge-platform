package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.knowledge.DocumentExtractPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 文档抽取节点（{@code DOCUMENT_EXTRACT_NODE}）的执行器（{@code #333}）。
 *
 * <p>★ <strong>它取的是库里已经解析好的正文</strong>，不是文件字节：{@code kb_document} 没有存原始字节（只有 {@code
 * parsed_content}），所以配置里给的是一个 <strong>文档 id</strong>（{@code docId}），执行器用它回库读 —— 详见 {@link
 * DocumentExtractPort#textOf(UUID)} 的注释与 {@code #333} 的评论。
 *
 * <p>★ <strong>为什么不是 base64、也不是服务器文件路径</strong>（{@code #333} 评论里定了，这里只复述判据）： base64 会撑爆 {@link
 * FlowState}（它是一张字符串表，进出都要序列化进快照）；服务器路径等于凭空多一个文件系统概念， 且前端传路径 = 越权读任意文件的口子。id 能借用数据本身的权限体系，路径不能。
 *
 * <p>★ <strong>三种情况都要说人话，不能合成一句"失败了"</strong>：
 *
 * <ul>
 *   <li>没配 {@code docId} —— 节点没选文档（配置问题，用户能自己改）；
 *   <li>文档不存在 / 已删 —— 选中的文档没了（数据问题）；
 *   <li>文档在但正文是空 —— 还没解析完或解析失败（★ 这两种要分开说，{@code status} 会带出来）。
 * </ul>
 *
 * <p>★ <strong>不静默写空串</strong>：写空串进 {@code document_markdown}，下游 {@code AI_CHAT_NODE} 会以为"有
 * 一份空资料"照常作答，模型对着空资料编 —— 用户看到的是"回答得笃定但完全不对"。明确失败至少让失败点落在配置或数据上。
 */
@Component
public class DocumentExtractNodeExecutor implements NodeExecutor {

  /** 抽出来的正文写进 state 的键。 */
  public static final String RESULT_KEY = "document_markdown";

  /**
   * 配置里给文档 id 的键名。
   *
   * <p>★ 用 camelCase 而不是本模块既有执行器的 snake_case（{@code dataset_id_list} / {@code reply_type}）：
   * 前端契约（{@code frontend/src/pages/workflow/lib/graph-format.ts}）给后端的是 camelCase，节点配置一路原样
   * 传到执行器。既有那几个 snake 键与契约对不上是另一笔账（{@code #333} 评论里记了），新节点不再跟着错。
   */
  static final String CONFIG_DOC_ID = "docId";

  private final ObjectProvider<DocumentExtractPort> extractPort;

  public DocumentExtractNodeExecutor(ObjectProvider<DocumentExtractPort> extractPort) {
    this.extractPort = extractPort;
  }

  @Override
  public String type() {
    return "DOCUMENT_EXTRACT_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    String docId = configureDocId(context.nodeConfig());
    UUID id;
    try {
      id = UUID.fromString(docId);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("文档抽取节点的 docId 不是合法 UUID：" + docId, ex);
    }

    // ★ 与 SearchDatasetNodeExecutor 同一条判据：端口缺席是部署问题，"没命中"是正常结果，两者不能长得一样。
    DocumentExtractPort port = extractPort.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("文档解析能力还没装配：没有 DocumentExtractPort 的实现");
    }

    Optional<DocumentExtractPort.DocumentText> found = port.textOf(id);
    // ★ 端口返回 null 视为实现违约，而不是"文档不存在"：契约写的是 Optional.empty()。
    if (found == null) {
      throw new IllegalStateException("文档解析端口返回了 null，契约要求 Optional.empty()");
    }
    DocumentExtractPort.DocumentText text =
        found.orElseThrow(() -> new IllegalStateException("文档不存在或已被删除：" + docId));

    String markdown = text.markdown() == null ? "" : text.markdown();
    if (markdown.isBlank()) {
      throw new IllegalStateException(
          "文档「" + text.filename() + "」还没有解析出正文（当前状态 " + text.status() + "），等解析完成或重新上传后再试");
    }
    context.state().write(RESULT_KEY, markdown, KeyUpdate.REPLACE);
  }

  /** 取配置里的 {@code docId}；没配就抛，不猜默认值（猜错了会解析成另一份文档）。 */
  private static String configureDocId(Map<String, Object> config) {
    Object raw = config == null ? null : config.get(CONFIG_DOC_ID);
    String docId = raw == null ? "" : raw.toString().trim();
    if (docId.isEmpty()) {
      throw new IllegalStateException("文档抽取节点没有选文档：在节点配置里给 docId");
    }
    return docId;
  }
}
