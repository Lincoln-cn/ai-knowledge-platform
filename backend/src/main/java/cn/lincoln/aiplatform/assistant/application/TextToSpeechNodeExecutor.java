package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.TextToSpeechPort;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 语音合成节点：把一段文字念成音频，音频落文件存储，流程变量里只留键。
 *
 * <h3>★ 为什么写"键"不写音频本身</h3>
 *
 * <p>{@link FlowState} 是 {@code Map<String,String>}，一次几 MB 的 base64 会跟着每一步的快照
 * （执行记录、暂停快照、恢复用图）复制下去。所以音频进 {@link FileStorePort}，流程里放键 —— 与 图像理解节点读键的口径保持一致，两个节点才能互相接上。
 *
 * <h3>★ 合成出一段空音频算失败，合成出一段静音算成功</h3>
 *
 * <p>前者是"上游没干活"（适配器会拦截零字节），后者是"要念的就是空字符串" —— 后者在这一层 就该被当成配置问题拒绝：节点拿不到文本，说明上游没写它要读的那个变量。
 */
@Component
public class TextToSpeechNodeExecutor implements NodeExecutor {

  /** 产出：合成音频在文件存储里的键。 */
  public static final String RESULT_KEY = WorkflowNodeKeys.Output.AUDIO_KEY;

  /** 产出：音频格式（{@code mp3}）。下游要按它决定扩展名与 Content-Type。 */
  public static final String FORMAT_KEY = WorkflowNodeKeys.Output.AUDIO_FORMAT;

  /** 读入：待念的文本默认从这个流程变量取（与 AI 对话节点的产出同名）。 */
  public static final String DEFAULT_SOURCE_KEY = WorkflowNodeKeys.Output.ANSWER;

  static final String CONFIG_MODEL_ID = WorkflowNodeKeys.Config.MODEL_ID;
  static final String CONFIG_VOICE = WorkflowNodeKeys.Config.VOICE;
  static final String CONFIG_SOURCE_KEY = WorkflowNodeKeys.Config.SOURCE_KEY;

  private final ObjectProvider<TextToSpeechPort> speech;
  private final ObjectProvider<FileStorePort> files;

  public TextToSpeechNodeExecutor(
      ObjectProvider<TextToSpeechPort> speech, ObjectProvider<FileStorePort> files) {
    this.speech = speech;
    this.files = files;
  }

  @Override
  public String type() {
    return "TEXT_TO_SPEECH_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) throws Exception {
    Map<String, Object> config = context.nodeConfig();
    UUID modelId = NodeConfigSupport.requireUuid(config, CONFIG_MODEL_ID, "语音合成节点", "modelId");
    String voice = NodeConfigSupport.text(config, CONFIG_VOICE);
    if (voice == null) {
      throw new IllegalStateException(
          "语音合成节点没有选音色：在节点配置里给 voice（各上游取值不同，如 alloy / nova / zh-CN-XiaoxiaoNeural）");
    }
    String sourceKey =
        NodeConfigSupport.keyOrDefault(config, CONFIG_SOURCE_KEY, DEFAULT_SOURCE_KEY);
    String content = context.state().read(sourceKey);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("语音合成节点没有拿到要念的文本：流程变量里没有「" + sourceKey + "」（上游节点要先产出这段文字）");
    }

    TextToSpeechPort port = speech.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("语音合成能力还没装配：没有 TextToSpeechPort 的实现");
    }
    FileStorePort store = files.getIfAvailable();
    if (store == null) {
      throw new IllegalStateException("文件存储还没装配：没有 FileStorePort 的实现");
    }

    Optional<TextToSpeechPort.Speech> synthesized = port.synthesize(modelId, content, voice);
    if (synthesized == null) {
      throw new IllegalStateException("语音合成端口返回了 null，契约要求 Optional.empty()");
    }
    TextToSpeechPort.Speech result =
        synthesized.orElseThrow(
            () ->
                new IllegalStateException(
                    "语音合成用的模型不可用：" + modelId + "。请确认它在模型管理里已加载、已启用，且种类是 TTS"));
    String key = store.store("speech." + result.format(), result.audio());
    context.state().write(RESULT_KEY, key, KeyUpdate.REPLACE);
    context.state().write(FORMAT_KEY, result.format(), KeyUpdate.REPLACE);
  }
}
