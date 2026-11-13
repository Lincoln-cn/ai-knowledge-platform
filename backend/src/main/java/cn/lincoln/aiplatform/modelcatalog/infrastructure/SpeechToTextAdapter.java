package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.platform.assistant.SpeechToTextPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 语音识别的实现（{@code #333} SPEECH_TO_TEXT_NODE）—— 走 {@code /audio/transcriptions}（multipart）。
 *
 * <p>★ 空文本是合法结果（{@code Optional.of("")}），不是失败：一段没人说话的录音确实该得到空文本。 把它报成"模型不可用"会让人去查配置，而实际什么都没坏。
 */
@Component
class SpeechToTextAdapter implements SpeechToTextPort {

  private final MultimodalTargets targets;
  private final UpstreamGateway gateway;
  private final ObjectMapper json = new ObjectMapper();

  SpeechToTextAdapter(MultimodalTargets targets, UpstreamGateway gateway) {
    this.targets = targets;
    this.gateway = gateway;
  }

  @Override
  public Optional<String> transcribe(UUID modelId, byte[] audio, String filename) {
    return targets
        .resolve(modelId, ModelKind.STT)
        .map(
            target -> {
              String body =
                  gateway.transcribe(
                      target.baseModel(),
                      audio,
                      filename,
                      target.apiBase(),
                      target.apiKey(),
                      target.authStyle());
              try {
                JsonNode text = json.readTree(body).path("text");
                if (!text.isTextual()) {
                  throw new UpstreamGateway.UpstreamUnreachableException(
                      "上游的转写响应里没有 text 字段。响应片段："
                          + (body.length() <= 200 ? body : body.substring(0, 200) + "…"));
                }
                return text.asText();
              } catch (JacksonException e) {
                throw new UpstreamGateway.UpstreamUnreachableException(
                    "上游返回的转写响应不是合法 JSON：" + e.getMessage(), e);
              }
            });
  }
}
