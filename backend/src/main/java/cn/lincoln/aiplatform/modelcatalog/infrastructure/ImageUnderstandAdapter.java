package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.platform.assistant.ImageUnderstandPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 视觉理解的实现（{@code #333} IMAGE_UNDERSTAND_NODE）—— 走对话模型的 {@code /chat/completions}。
 *
 * <p>★ 种类要求 {@code CHAT} 而不是"视觉模型"：协议里没有视觉模型这个种类， 视觉输入只是 content 变成数组（见 {@code
 * UpstreamGateway#chatWithImage}）。
 *
 * <p>★ 响应里没有 {@code choices[0].message.content} 时抛异常而不是回空串： 那说明上游给了一个我们不认识的形状（例如只回了
 * tool_calls），此时回空串会让节点 记录成"成功、输出为空"，而实际是协议不兼容。
 */
@Component
class ImageUnderstandAdapter implements ImageUnderstandPort {

  private final MultimodalTargets targets;
  private final UpstreamGateway gateway;
  private final ObjectMapper json = new ObjectMapper();

  ImageUnderstandAdapter(MultimodalTargets targets, UpstreamGateway gateway) {
    this.targets = targets;
    this.gateway = gateway;
  }

  @Override
  public Optional<String> describe(UUID modelId, byte[] image, String mimeType, String question) {
    return targets
        .resolve(modelId, ModelKind.CHAT)
        .map(
            target -> {
              String body =
                  gateway.chatWithImage(
                      target.baseModel(),
                      question,
                      image,
                      mimeType,
                      target.apiBase(),
                      target.apiKey(),
                      target.authStyle());
              try {
                JsonNode content =
                    json.readTree(body).path("choices").path(0).path("message").path("content");
                if (!content.isTextual()) {
                  throw new UpstreamGateway.UpstreamUnreachableException(
                      "上游的对话响应里没有 choices[0].message.content —— 图片没被当成消息内容处理。响应片段："
                          + (body.length() <= 200 ? body : body.substring(0, 200) + "…"));
                }
                return content.asText();
              } catch (JacksonException e) {
                throw new UpstreamGateway.UpstreamUnreachableException(
                    "上游返回的对话响应不是合法 JSON：" + e.getMessage(), e);
              }
            });
  }
}
