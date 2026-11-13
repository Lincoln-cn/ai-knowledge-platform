package cn.lincoln.aiplatform.assistant.application;

import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.absent;
import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.ImageUnderstandPort;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 图像理解节点：读文件键、问模型、把描述写进流程变量。 */
class ImageUnderstandNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
  private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  /** 记下入参、回放预设结果的替身端口。 */
  private static final class RecordingPort implements ImageUnderstandPort {
    UUID modelId;
    byte[] image;
    String question;
    int calls;
    boolean empty;
    boolean contractViolation;

    @Override
    public Optional<String> describe(UUID modelId, byte[] image, String mimeType, String question) {
      this.modelId = modelId;
      this.image = image;
      this.question = question;
      this.calls++;
      if (contractViolation) {
        return null;
      }
      return empty ? Optional.empty() : Optional.of("一张合同扫描件，页眉有公司名。");
    }
  }

  @Test
  @DisplayName("把图片字节交给端口，把描述写进 image_description")
  void writesDescription() throws Exception {
    RecordingPort port = new RecordingPort();
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    store.files.put("image.png#1", new byte[] {1, 2, 3});
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.IMAGE_KEY, "image.png#1", KeyUpdate.REPLACE);

    new ImageUnderstandNodeExecutor(providerOf(port), providerOf(store))
        .execute(context(state, Map.of("modelId", MODEL_ID.toString())));

    assertThat(port.modelId).isEqualTo(MODEL_ID);
    assertThat(port.image).containsExactly(1, 2, 3);
    assertThat(port.question).isEqualTo(ImageUnderstandNodeExecutor.FALLBACK_QUESTION);
    assertThat(state.read(ImageUnderstandNodeExecutor.RESULT_KEY)).isEqualTo("一张合同扫描件，页眉有公司名。");
  }

  @Test
  @DisplayName("配置里给了问题和图片变量：用配置的，不用默认")
  void honoursConfiguredQuestionAndKey() throws Exception {
    RecordingPort port = new RecordingPort();
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    store.files.put("photo.jpg#1", new byte[] {9});
    FlowState state = new FlowState();
    state.write("uploaded_image", "photo.jpg#1", KeyUpdate.REPLACE);

    new ImageUnderstandNodeExecutor(providerOf(port), providerOf(store))
        .execute(
            context(
                state,
                Map.of(
                    "modelId", MODEL_ID.toString(),
                    "question", "这张图里有几处签名？",
                    "imageKey", "uploaded_image")));

    assertThat(port.question).isEqualTo("这张图里有几处签名？");
    assertThat(store.readKeys).containsExactly("photo.jpg#1");
  }

  @Test
  @DisplayName("没选模型：说清要在配置里给 modelId，并且不留半截变量")
  void rejectsMissingModel() {
    FlowState state = new FlowState();
    assertThatThrownBy(
            () ->
                new ImageUnderstandNodeExecutor(providerOf(new RecordingPort()), absent())
                    .execute(context(state, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("modelId");
    assertThat(state.asMap()).isEmpty();
  }

  @Test
  @DisplayName("modelId 不是 UUID：报出值本身")
  void rejectsMalformedModel() {
    assertThatThrownBy(
            () ->
                new ImageUnderstandNodeExecutor(providerOf(new RecordingPort()), absent())
                    .execute(context(new FlowState(), Map.of("modelId", "不是-uuid"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是-uuid");
  }

  @Test
  @DisplayName("流程变量里没有图片键：明确说是上游没给图，不是模型有问题")
  void rejectsMissingImage() {
    assertThatThrownBy(
            () ->
                new ImageUnderstandNodeExecutor(
                        providerOf(new RecordingPort()),
                        providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(new FlowState(), Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("image_key");
  }

  @Test
  @DisplayName("端口没有实现：说清是没装配")
  void rejectsMissingPort() {
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.IMAGE_KEY, "image.png#1", KeyUpdate.REPLACE);
    assertThatThrownBy(
            () ->
                new ImageUnderstandNodeExecutor(
                        absent(), providerOf(new NodeExecutorTestSupport.RecordingFileStore()))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ImageUnderstandPort");
  }

  @Test
  @DisplayName("文件存储没有实现：说的是存储没装配，不是模型不可用")
  void rejectsMissingFileStore() {
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.IMAGE_KEY, "image.png#1", KeyUpdate.REPLACE);
    assertThatThrownBy(
            () ->
                new ImageUnderstandNodeExecutor(providerOf(new RecordingPort()), absent())
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("FileStorePort");
  }

  @Test
  @DisplayName("模型调不了（端口给空）：文案指向模型管理页，并说清视觉是对话模型的扩展")
  void rejectsUnavailableModel() {
    RecordingPort port = new RecordingPort();
    port.empty = true;
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    store.files.put("image.png#1", new byte[] {1});
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.IMAGE_KEY, "image.png#1", KeyUpdate.REPLACE);

    assertThatThrownBy(
            () ->
                new ImageUnderstandNodeExecutor(providerOf(port), providerOf(store))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(MODEL_ID.toString())
        .hasMessageContaining("对话模型");
  }

  @Test
  @DisplayName("端口违约返回 null：报出契约要求，而不是 NPE")
  void rejectsContractViolation() {
    RecordingPort port = new RecordingPort();
    port.contractViolation = true;
    NodeExecutorTestSupport.RecordingFileStore store =
        new NodeExecutorTestSupport.RecordingFileStore();
    store.files.put("image.png#1", new byte[] {1});
    FlowState state = new FlowState();
    state.write(WorkflowNodeKeys.Output.IMAGE_KEY, "image.png#1", KeyUpdate.REPLACE);

    assertThatThrownBy(
            () ->
                new ImageUnderstandNodeExecutor(providerOf(port), providerOf(store))
                    .execute(context(state, Map.of("modelId", MODEL_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Optional.empty()");
  }

  private static WorkflowNodeContext context(FlowState state, Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-image", "IMAGE_UNDERSTAND_NODE", "看看这张图", state, config);
  }
}
