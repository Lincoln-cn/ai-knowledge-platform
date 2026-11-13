package cn.lincoln.aiplatform.assistant.application;

import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.absent;
import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.SpeechToTextPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 语音识别节点：读音频键、写识别文本。 */
class SpeechToTextNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
  private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

  private static final class RecordingPort implements SpeechToTextPort {
    UUID modelId;
    byte[] audio;
    String filename;
    int calls;
    String answer = "这是一段测试音频的转写。";
    boolean contractViolation;

    @Override
    public Optional<String> transcribe(UUID modelId, byte[] audio, String filename) {
      this.modelId = modelId;
      this.audio = audio;
      this.filename = filename;
      this.calls++;
      if (contractViolation) {
        return null;
      }
      return Optional.of(answer);
    }
  }

  @Test
  @DisplayName("读 audio_key 指向的音频，把转写写进 speech_transcript")
  void writesTranscript() throws Exception {
    RecordingPort port = new RecordingPort();
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    store.files.put("speech.mp3#1", new byte[] {1, 2, 3});
    FlowState state = new FlowState();
    state.write(SpeechToTextNodeExecutor.DEFAULT_AUDIO_KEY, "speech.mp3#1", KeyUpdate.REPLACE);

    new SpeechToTextNodeExecutor(providerOf(port), providerOf(store))
        .execute(context(state, Map.of("modelId", MODEL_ID.toString())));

    assertThat(port.modelId).isEqualTo(MODEL_ID);
    assertThat(port.audio).containsExactly(1, 2, 3);
    assertThat(port.filename).isEqualTo(SpeechToTextNodeExecutor.DEFAULT_FILENAME);
    assertThat(state.read(SpeechToTextNodeExecutor.RESULT_KEY)).isEqualTo("这是一段测试音频的转写。");
  }

  @Test
  @DisplayName("空文本是成功：一段没人说话的录音应该得到空字符串然后继续")
  void emptyTranscriptIsSuccess() throws Exception {
    RecordingPort port = new RecordingPort();
    port.answer = "";
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    store.files.put("speech.mp3#1", new byte[] {1});
    FlowState state = new FlowState();
    state.write(SpeechToTextNodeExecutor.DEFAULT_AUDIO_KEY, "speech.mp3#1", KeyUpdate.REPLACE);

    new SpeechToTextNodeExecutor(providerOf(port), providerOf(store))
        .execute(context(state, Map.of("modelId", MODEL_ID.toString())));

    assertThat(state.read(SpeechToTextNodeExecutor.RESULT_KEY)).isEmpty();
  }

  @Test
  @DisplayName("音频键与文件名都可以改")
  void honoursConfiguredKeys() throws Exception {
    RecordingPort port = new RecordingPort();
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    store.files.put("voice.wav#1", new byte[] {7});
    FlowState state = new FlowState();
    state.write("user_audio", "voice.wav#1", KeyUpdate.REPLACE);

    new SpeechToTextNodeExecutor(providerOf(port), providerOf(store))
        .execute(
            context(
                state,
                Map.of(
                    "modelId", MODEL_ID.toString(),
                    "audioKey", "user_audio",
                    "filename", "voice.wav")));

    assertThat(store.readKeys).containsExactly("voice.wav#1");
    assertThat(port.filename).isEqualTo("voice.wav");
  }

  @Test
  @DisplayName("没有音频键：指向具体是哪个流程变量空了，并提示两条路")
  void rejectsMissingAudioKey() {
    FlowState state = new FlowState();
    assertThatThrownBy(
            () ->
                new SpeechToTextNodeExecutor(
                        providerOf(new RecordingPort()),
                        providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(SpeechToTextNodeExecutor.DEFAULT_AUDIO_KEY)
        .hasMessageContaining("语音合成节点");
  }

  @Test
  @DisplayName("端口没有实现：说清是没装配")
  void rejectsMissingPort() {
    FlowState state = new FlowState();
    state.write(SpeechToTextNodeExecutor.DEFAULT_AUDIO_KEY, "speech.mp3#1", KeyUpdate.REPLACE);
    assertThatThrownBy(
            () ->
                new SpeechToTextNodeExecutor(
                        absent(), providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SpeechToTextPort");
  }

  @Test
  @DisplayName("端口违约返回 null：报出契约要求")
  void rejectsContractViolation() {
    RecordingPort port = new RecordingPort();
    port.contractViolation = true;
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    store.files.put("speech.mp3#1", new byte[] {1});
    FlowState state = new FlowState();
    state.write(SpeechToTextNodeExecutor.DEFAULT_AUDIO_KEY, "speech.mp3#1", KeyUpdate.REPLACE);

    assertThatThrownBy(
            () ->
                new SpeechToTextNodeExecutor(providerOf(port), providerOf(store))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Optional.empty()");
  }

  private static WorkflowNodeContext context(FlowState state, Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-stt", "SPEECH_TO_TEXT_NODE", "入口输入", state, config);
  }
}
