package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** {@link NodeConfigReader} 的纯函数级读数（离线、无容器）。 */
class NodeConfigReaderTest {

  private final NodeConfigReader reader = new NodeConfigReader();
  private final ObjectMapper mapper = new ObjectMapper();

  private NodeConfigReader.NodeConfig read(String json) {
    return reader.read(mapper.readTree(json));
  }

  @Test
  @DisplayName("画布发来的 config 被读成视图：modelId 与 kbIds 都对上")
  void readsModelAndKnowledgeBases() {
    NodeConfigReader.NodeConfig config =
        read("{\"id\":\"a1\",\"type\":\"AI_CHAT_NODE\",\"config\":{\"modelId\":\"m-1\"}}");
    assertThat(config.modelId()).isEqualTo("m-1");
    assertThat(config.hasModel()).isTrue();
    assertThat(config.kbIds()).isEmpty();
  }

  @Test
  @DisplayName("没有 config、config 不是对象、字段类型不对 —— 一律读成空视图，不抛")
  void missingOrMalformedConfigYieldsEmptyView() {
    assertThat(read("{\"id\":\"a1\",\"type\":\"AI_CHAT_NODE\"}").hasModel()).isFalse();
    assertThat(read("{\"id\":\"a1\",\"config\":\"oops\"}").hasModel()).isFalse();
    assertThat(read("{\"id\":\"a1\",\"config\":{\"modelId\":42}}").modelId()).isNull();
    assertThat(reader.read(null).hasModel()).isFalse();
  }

  @Test
  @DisplayName("kbIds 里的空串与非字符串被丢掉；检索类节点据此判断有没有来源")
  void knowledgeBaseIdsAreFiltered() {
    NodeConfigReader.NodeConfig config =
        read("{\"config\":{\"kbIds\":[\"kb-1\",\"\",7,\"kb-2\"]}}");
    assertThat(config.kbIds()).containsExactly("kb-1", "kb-2");
    assertThat(config.hasSource()).isTrue();
  }

  @Test
  @DisplayName("选应用也算有来源：hasSource 是「知识库或应用」二选一")
  void applicationCountsAsSource() {
    assertThat(read("{\"config\":{\"applicationId\":\"app-1\"}}").hasSource()).isTrue();
    assertThat(read("{\"config\":{\"applicationId\":\"  \"}}").hasSource()).isFalse();
  }
}
