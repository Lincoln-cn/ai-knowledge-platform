package cn.lincoln.aiplatform.assistant.application;

import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.absent;
import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.TextToSpeechPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 语音合成节点：文本进、音频落存储、流程变量里留键与格式。 */
class TextToSpeechNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
  private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  private static final byte[] AUDIO = {82, 73, 70, 70};

  private static final class RecordingPort implements TextToSpeechPort {
    UUID modelId;
    String text;
    String voice;
    int calls;
    boolean empty;
    boolean contractViolation;

    @Override
    public Optional<Speech> synthesize(UUID modelId, String text, String voice) {
      this.modelId = modelId;
      this.text = text;
      this.voice = voice;
      this.calls++;
      if (contractViolation) {
        return null;
      }
      return empty ? Optional.empty() : Optional.of(new Speech(AUDIO, "mp3"));
    }
  }

  @Test
  @DisplayName("念 answer 里的文字，音频进文件存储，流程变量里只留键与格式")
  void writesAudioKeyAndFormat() throws Exception {
    RecordingPort port = new RecordingPort();
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.ANSWER, "按制度执行。", KeyUpdate.REPLACE);

    new TextToSpeechNodeExecutor(providerOf(port), providerOf(store))
        .execute(context(state, Map.of("modelId", MODEL_ID.toString(), "voice", "alloy")));

    assertThat(port.modelId).isEqualTo(MODEL_ID);
    assertThat(port.text).isEqualTo("按制度执行。");
    assertThat(port.voice).isEqualTo("alloy");
    assertThat(store.storedNames).containsExactly("speech.mp3");
    assertThat(state.read(TextToSpeechNodeExecutor.RESULT_KEY)).isEqualTo("speech.mp3#1");
    assertThat(state.read(TextToSpeechNodeExecutor.FORMAT_KEY)).isEqualTo("mp3");
  }

  @Test
  @DisplayName("流程变量里没有音频的字节 —— 只有键：断言这条约定没被破坏")
  void keepsBytesOutOfFlowState() throws Exception {
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.ANSWER, "念这句", KeyUpdate.REPLACE);

    new TextToSpeechNodeExecutor(providerOf(new RecordingPort()), providerOf(store))
        .execute(context(state, Map.of("modelId", MODEL_ID.toString(), "voice", "alloy")));

    assertThat(state.asMap().values())
        .as("流程变量里不该出现二进制内容：它会跟着每一步快照复制")
        .allSatisfy(value -> assertThat(value).doesNotContain("\u0000"));
  }

  @Test
  @DisplayName("sourceKey 可以指到任意变量")
  void readsConfiguredSourceKey() throws Exception {
    RecordingPort port = new RecordingPort();
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    FlowState state = new FlowState();
    state.write("summary", "这是一段摘要", KeyUpdate.REPLACE);

    new TextToSpeechNodeExecutor(providerOf(port), providerOf(store))
        .execute(
            context(
                state,
                Map.of("modelId", MODEL_ID.toString(), "voice", "alloy", "sourceKey", "summary")));

    assertThat(port.text).isEqualTo("这是一段摘要");
  }

  @Test
  @DisplayName("没选音色：voice 是必填，且文案要说清各上游取值不同")
  void rejectsMissingVoice() {
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.ANSWER, "有文字", KeyUpdate.REPLACE);
    assertThatThrownBy(
            () ->
                new TextToSpeechNodeExecutor(
                        providerOf(new RecordingPort()),
                        providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("voice")
        .hasMessageContaining("各上游取值不同");
  }

  @Test
  @DisplayName("没有要念的文本：指向具体是哪个流程变量空了")
  void rejectsMissingText() {
    FlowState state = new FlowState();
    assertThatThrownBy(
            () ->
                new TextToSpeechNodeExecutor(
                        providerOf(new RecordingPort()),
                        providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(
                        context(state, Map.of("modelId", MODEL_ID.toString(), "voice", "alloy"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(WorkflowNodeKeys.Output.ANSWER);
  }

  @Test
  @DisplayName("端口没有实现：说清是没装配")
  void rejectsMissingPort() {
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.ANSWER, "有文字", KeyUpdate.REPLACE);
    assertThatThrownBy(
            () ->
                new TextToSpeechNodeExecutor(
                        absent(), providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(
                        context(state, Map.of("modelId", MODEL_ID.toString(), "voice", "alloy"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TextToSpeechPort");
  }

  @Test
  @DisplayName("模型调不了：文案指向模型管理页与 TTS 种类")
  void rejectsUnavailableModel() {
    RecordingPort port = new RecordingPort();
    port.empty = true;
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.ANSWER, "有文字", KeyUpdate.REPLACE);

    assertThatThrownBy(
            () ->
                new TextToSpeechNodeExecutor(
                        providerOf(port),
                        providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(
                        context(state, Map.of("modelId", MODEL_ID.toString(), "voice", "alloy"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(MODEL_ID.toString())
        .hasMessageContaining("TTS");
  }

  private static WorkflowNodeContext context(FlowState state, Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-tts", "TEXT_TO_SPEECH_NODE", "入口输入", state, config);
  }
}
