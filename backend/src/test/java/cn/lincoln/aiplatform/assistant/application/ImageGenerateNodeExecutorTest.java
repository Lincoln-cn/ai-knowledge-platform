package cn.lincoln.aiplatform.assistant.application;

import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.absent;
import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.ImageGeneratePort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 图像生成节点：提示词进、图片落存储、写键与类型。 */
class ImageGenerateNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f4");
  private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47};

  private static final class RecordingPort implements ImageGeneratePort {
    UUID modelId;
    String prompt;
    String size;
    int calls;
    boolean empty;
    boolean contractViolation;

    @Override
    public Optional<Generated> generate(UUID modelId, String prompt, String size) {
      this.modelId = modelId;
      this.prompt = prompt;
      this.size = size;
      this.calls++;
      if (contractViolation) {
        return null;
      }
      return empty ? Optional.empty() : Optional.of(new Generated(PNG, "image/png"));
    }
  }

  @Test
  @DisplayName("读 query 当提示词，图片进文件存储，写 image_key 与 image_mime")
  void writesImageKeyAndMime() throws Exception {
    RecordingPort port = new RecordingPort();
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.QUERY, "一只在合同上盖章的猫", KeyUpdate.REPLACE);

    new ImageGenerateNodeExecutor(providerOf(port), providerOf(store))
        .execute(context(state, Map.of("modelId", MODEL_ID.toString(), "size", "1024x1024")));

    assertThat(port.modelId).isEqualTo(MODEL_ID);
    assertThat(port.prompt).isEqualTo("一只在合同上盖章的猫");
    assertThat(port.size).isEqualTo("1024x1024");
    assertThat(store.storedNames).containsExactly("image.png");
    assertThat(state.read(ImageGenerateNodeExecutor.RESULT_KEY)).isEqualTo("image.png#1");
    assertThat(state.read(ImageGenerateNodeExecutor.MIME_KEY)).isEqualTo("image/png");
  }

  @Test
  @DisplayName("promptKey 可以指到任意变量")
  void readsConfiguredPromptKey() throws Exception {
    RecordingPort port = new RecordingPort();
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    FlowState state = new FlowState();
    state.write("scenario", "雨天的税务局窗口", KeyUpdate.REPLACE);

    new ImageGenerateNodeExecutor(providerOf(port), providerOf(store))
        .execute(context(state, Map.of("modelId", MODEL_ID.toString(), "promptKey", "scenario")));

    assertThat(port.prompt).isEqualTo("雨天的税务局窗口");
    assertThat(port.size).isNull();
  }

  @Test
  @DisplayName("没有提示词：说清是哪个流程变量空了，以及怎么改")
  void rejectsMissingPrompt() {
    assertThatThrownBy(
            () ->
                new ImageGenerateNodeExecutor(
                        providerOf(new RecordingPort()),
                        providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(new FlowState(), Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(WorkflowNodeKeys.Output.QUERY)
        .hasMessageContaining("promptKey");
  }

  @Test
  @DisplayName("端口没有实现：说清是没装配")
  void rejectsMissingPort() {
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.QUERY, "随便画点什么", KeyUpdate.REPLACE);
    assertThatThrownBy(
            () ->
                new ImageGenerateNodeExecutor(
                        absent(), providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ImageGeneratePort");
  }

  @Test
  @DisplayName("模型调不了：文案指向模型管理页与 IMAGE 种类")
  void rejectsUnavailableModel() {
    RecordingPort port = new RecordingPort();
    port.empty = true;
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.QUERY, "随便画点什么", KeyUpdate.REPLACE);

    assertThatThrownBy(
            () ->
                new ImageGenerateNodeExecutor(
                        providerOf(port),
                        providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(MODEL_ID.toString())
        .hasMessageContaining("IMAGE");
  }

  @Test
  @DisplayName("端口违约返回 null：报出契约要求")
  void rejectsContractViolation() {
    RecordingPort port = new RecordingPort();
    port.contractViolation = true;
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.QUERY, "随便画点什么", KeyUpdate.REPLACE);

    assertThatThrownBy(
            () ->
                new ImageGenerateNodeExecutor(
                        providerOf(port),
                        providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Optional.empty()");
  }

  private static WorkflowNodeContext context(FlowState state, Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-image-gen", "IMAGE_GENERATE_NODE", "入口输入", state, config);
  }
}
