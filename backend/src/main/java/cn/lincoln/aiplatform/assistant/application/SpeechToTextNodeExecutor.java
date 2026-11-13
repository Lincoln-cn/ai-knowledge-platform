package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.SpeechToTextPort;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 语音识别节点：把音频转成文字。
 *
 * <h3>★ 它读"文件键"，与语音合成节点写的那一格是同一个名字</h3>
 *
 * <p>{@link TextToSpeechNodeExecutor} 写 {@code audio_key}，这里默认读 {@code audio_key} ——
 * 两个节点串起来不需要用户改任何配置。这不是巧合而是约定：同一类媒体在流程里只有一个约定键名。
 *
 * <h3>★ 空文本是成功</h3>
 *
 * <p>一段没人说话的录音应该得到空字符串，然后继续往下跑。把它当失败会让人以为模型坏了， 而实际什么都没坏 —— 所以这里只拦"拿不到音频键"和"模型调不了"两件事。
 */
@Component
public class SpeechToTextNodeExecutor implements NodeExecutor {

  /** 产出：识别出的文本。 */
  public static final String RESULT_KEY = WorkflowNodeKeys.Output.SPEECH_TRANSCRIPT;

  /** 读入：音频在文件存储里的键（与语音合成节点的产出同名）。 */
  public static final String DEFAULT_AUDIO_KEY = WorkflowNodeKeys.Output.AUDIO_KEY;

  static final String CONFIG_MODEL_ID = WorkflowNodeKeys.Config.MODEL_ID;
  static final String CONFIG_AUDIO_KEY = WorkflowNodeKeys.Config.AUDIO_KEY;
  static final String CONFIG_FILENAME = WorkflowNodeKeys.Config.FILENAME;

  /** 默认文件名：上游靠扩展名判容器格式，所以这个名字必须带扩展名。 */
  static final String DEFAULT_FILENAME = "audio.mp3";

  private final ObjectProvider<SpeechToTextPort> transcribe;
  private final ObjectProvider<FileStorePort> files;

  public SpeechToTextNodeExecutor(
      ObjectProvider<SpeechToTextPort> transcribe, ObjectProvider<FileStorePort> files) {
    this.transcribe = transcribe;
    this.files = files;
  }

  @Override
  public String type() {
    return "SPEECH_TO_TEXT_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) throws Exception {
    Map<String, Object> config = context.nodeConfig();
    UUID modelId = NodeConfigSupport.requireUuid(config, CONFIG_MODEL_ID, "语音识别节点", "modelId");
    String audioKey = NodeConfigSupport.keyOrDefault(config, CONFIG_AUDIO_KEY, DEFAULT_AUDIO_KEY);
    String filename = NodeConfigSupport.keyOrDefault(config, CONFIG_FILENAME, DEFAULT_FILENAME);

    String key = context.state().read(audioKey);
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("语音识别节点没有收到音频：流程变量里没有「" + audioKey + "」（先放一个语音合成节点，或把音频键改名）");
    }

    SpeechToTextPort port = transcribe.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("语音识别能力还没装配：没有 SpeechToTextPort 的实现");
    }
    FileStorePort store = files.getIfAvailable();
    if (store == null) {
      throw new IllegalStateException("文件存储还没装配：没有 FileStorePort 的实现");
    }

    byte[] audio = store.read(key.trim());
    Optional<String> result = port.transcribe(modelId, audio, filename);
    if (result == null) {
      throw new IllegalStateException("语音识别端口返回了 null，契约要求 Optional.empty()");
    }
    String text =
        result.orElseThrow(
            () ->
                new IllegalStateException(
                    "语音识别用的模型不可用：" + modelId + "。请确认它在模型管理里已加载、已启用，且种类是 STT"));
    context.state().write(RESULT_KEY, text, KeyUpdate.REPLACE);
  }
}
