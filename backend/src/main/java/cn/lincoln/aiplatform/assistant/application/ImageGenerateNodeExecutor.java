package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.ImageGeneratePort;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 图像生成节点：提示词进、图片落存储、写两个流程变量（键 + 类型）。
 *
 * <h3>★ 提示词从流程变量取，不从配置里写死</h3>
 *
 * <p>图像生成的上游几乎总是用户的问题或模型总结出来的画面描述，所以默认读 {@code query}； 配置里给 {@code promptKey}
 * 可以改成任意变量。写死在配置里等于这个节点只能生成同一张图， 那它就不该在流程里。
 *
 * <h3>★ 为什么同时写 {@code image_mime}</h3>
 *
 * <p>只写键的话，下游拼 URL / Content-Type 时得猜图片类型。猜 png 而实际是 jpeg 的后果不是报错， 是浏览器拿 Content-Type 去解码失败 ——
 * 一张"在磁盘上存在、在页面里显示不出来"的图。
 */
@Component
public class ImageGenerateNodeExecutor implements NodeExecutor {

  /** 产出：图片在文件存储里的键。 */
  public static final String RESULT_KEY = WorkflowNodeKeys.Output.IMAGE_KEY;

  /** 产出：图片类型（{@code image/png}）。 */
  public static final String MIME_KEY = WorkflowNodeKeys.Output.IMAGE_MIME;

  /** 读入：提示词默认从流程变量 {@code query} 取。 */
  public static final String DEFAULT_PROMPT_KEY = WorkflowNodeKeys.Output.QUERY;

  static final String CONFIG_MODEL_ID = WorkflowNodeKeys.Config.MODEL_ID;
  static final String CONFIG_SIZE = WorkflowNodeKeys.Config.SIZE;
  static final String CONFIG_PROMPT_KEY = WorkflowNodeKeys.Config.PROMPT_KEY;

  private final ObjectProvider<ImageGeneratePort> generate;
  private final ObjectProvider<FileStorePort> files;

  public ImageGenerateNodeExecutor(
      ObjectProvider<ImageGeneratePort> generate, ObjectProvider<FileStorePort> files) {
    this.generate = generate;
    this.files = files;
  }

  @Override
  public String type() {
    return "IMAGE_GENERATE_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) throws Exception {
    Map<String, Object> config = context.nodeConfig();
    UUID modelId = NodeConfigSupport.requireUuid(config, CONFIG_MODEL_ID, "图像生成节点", "modelId");
    String size = NodeConfigSupport.text(config, CONFIG_SIZE);
    String promptKey =
        NodeConfigSupport.keyOrDefault(config, CONFIG_PROMPT_KEY, DEFAULT_PROMPT_KEY);

    String prompt = context.state().read(promptKey);
    if (prompt == null || prompt.isBlank()) {
      throw new IllegalStateException(
          "图像生成节点没有拿到提示词：流程变量里没有「" + promptKey + "」（在节点配置里把 promptKey 指到一个有值的变量）");
    }

    ImageGeneratePort port = generate.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("图像生成能力还没装配：没有 ImageGeneratePort 的实现");
    }
    FileStorePort store = files.getIfAvailable();
    if (store == null) {
      throw new IllegalStateException("文件存储还没装配：没有 FileStorePort 的实现");
    }

    Optional<ImageGeneratePort.Generated> generated = port.generate(modelId, prompt, size);
    if (generated == null) {
      throw new IllegalStateException("图像生成端口返回了 null，契约要求 Optional.empty()");
    }
    ImageGeneratePort.Generated image =
        generated.orElseThrow(
            () ->
                new IllegalStateException(
                    "图像生成用的模型不可用：" + modelId + "。请确认它在模型管理里已加载、已启用，且种类是 IMAGE"));
    String key = store.store("image.png", image.bytes());
    context.state().write(RESULT_KEY, key, KeyUpdate.REPLACE);
    context.state().write(MIME_KEY, image.mimeType(), KeyUpdate.REPLACE);
  }
}
