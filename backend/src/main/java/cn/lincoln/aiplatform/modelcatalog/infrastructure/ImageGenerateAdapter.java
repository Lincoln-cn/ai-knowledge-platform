package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.platform.assistant.ImageGeneratePort;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 图像生成的实现（{@code #333} IMAGE_GENERATE_NODE）—— 走 {@code /images/generations}。
 *
 * <p>★ 只接受 {@code b64_json}：上游给 URL 时要么下载失败、要么那地址内网访问不到， 而两种失败都会表现成"图生成好了但显示不出来"。所以请求里就写死 {@code
 * response_format}， 回包里没有 {@code b64_json} 就明确报错，不去猜 {@code url} 能不能用。
 *
 * <p>★ 图片类型按上游给的 {@code data[0].b64_json} 无法判断，固定 {@code image/png}： OpenAI 兼容的图像接口默认就是
 * png；要别的格式得在提示词里说，接口不提供该参数。
 */
@Component
class ImageGenerateAdapter implements ImageGeneratePort {

  private final MultimodalTargets targets;
  private final UpstreamGateway gateway;
  private final ObjectMapper json = new ObjectMapper();

  ImageGenerateAdapter(MultimodalTargets targets, UpstreamGateway gateway) {
    this.targets = targets;
    this.gateway = gateway;
  }

  @Override
  public Optional<Generated> generate(UUID modelId, String prompt, String size) {
    return targets
        .resolve(modelId, ModelKind.IMAGE)
        .map(
            target -> {
              String body =
                  gateway.images(
                      target.baseModel(),
                      prompt,
                      size,
                      target.apiBase(),
                      target.apiKey(),
                      target.authStyle());
              try {
                JsonNode first = json.readTree(body).path("data").path(0);
                JsonNode encoded = first.path("b64_json");
                if (!encoded.isTextual() || encoded.asText().isEmpty()) {
                  throw new UpstreamGateway.UpstreamUnreachableException(
                      "上游没有返回 data[0].b64_json（可能只回了 url）。响应片段："
                          + (body.length() <= 200 ? body : body.substring(0, 200) + "…"));
                }
                return new Generated(Base64.getDecoder().decode(encoded.asText()), "image/png");
              } catch (JacksonException e) {
                throw new UpstreamGateway.UpstreamUnreachableException(
                    "上游返回的图像响应不是合法 JSON：" + e.getMessage(), e);
              } catch (IllegalArgumentException e) {
                // Base64 解不开：上游把别的东西塞进了 b64_json 字段
                throw new UpstreamGateway.UpstreamUnreachableException(
                    "上游返回的 b64_json 不是合法 Base64：" + e.getMessage(), e);
              }
            });
  }
}
