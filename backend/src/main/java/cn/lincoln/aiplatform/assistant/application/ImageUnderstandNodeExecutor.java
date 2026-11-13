package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.ImageUnderstandPort;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 图像理解节点：读一张图 + 一句问题，把模型的回答写进流程变量。
 *
 * <h3>★ 它读的是"文件键"而不是"图片字节"</h3>
 *
 * <p>{@link FlowState} 是 {@code Map<String,String>}，把图片 base64 塞进去等于让每一步都快照一份 几 MB 的字符串 ——
 * 执行记录、暂停快照、恢复用图全都要带着它走。所以约定：上游节点（或调用方） 先把图存进文件存储，流程变量里只放 <strong>键</strong>。
 *
 * <p>★ 键的类型是 {@code String} 而不是 UUID：{@link FileStorePort} 的键是"相对键"（实现自定形状）， 解释它的只能是存储实现，不是这里。
 *
 * <h3>★ 失败文案分四档，因为四种失败要改的地方不一样</h3>
 *
 * <p>没选模型 / 没收到图 / 模型调不了 / 上游报错 —— 合成一句"图像理解失败"会让 用户去猜是哪一步，而这四种分别对应：节点配置、上游连线、模型管理页、模型服务本身。
 */
@Component
public class ImageUnderstandNodeExecutor implements NodeExecutor {

  /** 产出：模型对这张图的描述。 */
  public static final String RESULT_KEY = WorkflowNodeKeys.Output.IMAGE_DESCRIPTION;

  /** 读入：图片在文件存储里的键。 */
  public static final String DEFAULT_IMAGE_KEY = WorkflowNodeKeys.Output.IMAGE_KEY;

  static final String CONFIG_MODEL_ID = WorkflowNodeKeys.Config.MODEL_ID;
  static final String CONFIG_QUESTION = WorkflowNodeKeys.Config.QUESTION;
  static final String CONFIG_IMAGE_KEY = WorkflowNodeKeys.Config.IMAGE_KEY;

  /** 模型没被问什么时问这个 —— 一句中性的描述请求，不需要用户每次都想。 */
  static final String FALLBACK_QUESTION = "请客观描述这张图片的内容。";

  private final ObjectProvider<ImageUnderstandPort> understand;
  private final ObjectProvider<FileStorePort> files;

  public ImageUnderstandNodeExecutor(
      ObjectProvider<ImageUnderstandPort> understand, ObjectProvider<FileStorePort> files) {
    this.understand = understand;
    this.files = files;
  }

  @Override
  public String type() {
    return "IMAGE_UNDERSTAND_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) throws Exception {
    Map<String, Object> config = context.nodeConfig();
    UUID modelId = NodeConfigSupport.requireUuid(config, CONFIG_MODEL_ID, "图像理解节点", "modelId");
    String question = NodeConfigSupport.text(config, CONFIG_QUESTION);
    if (question == null) {
      question = FALLBACK_QUESTION;
    }
    String imageKey = NodeConfigSupport.keyOrDefault(config, CONFIG_IMAGE_KEY, DEFAULT_IMAGE_KEY);
    String key = context.state().read(imageKey);
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("图像理解节点没有收到图片：流程变量里没有「" + imageKey + "」（先上传图片，或把上游节点的图片键改名）");
    }

    ImageUnderstandPort port = understand.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("图像理解能力还没装配：没有 ImageUnderstandPort 的实现");
    }
    FileStorePort store = files.getIfAvailable();
    if (store == null) {
      throw new IllegalStateException("文件存储还没装配：没有 FileStorePort 的实现");
    }

    byte[] bytes = store.read(key.trim());
    Optional<String> description = port.describe(modelId, bytes, null, question);
    if (description == null) {
      throw new IllegalStateException("图像理解端口返回了 null，契约要求 Optional.empty()");
    }
    String text =
        description.orElseThrow(
            () ->
                new IllegalStateException(
                    "图像理解用的模型不可用："
                        + modelId
                        + "。"
                        + "请确认它在模型管理里已加载、已启用，且是对话模型（视觉输入是对话模型的扩展，不是另一种种类）"));
    context.state().write(RESULT_KEY, text, KeyUpdate.REPLACE);
  }
}
