package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.platform.assistant.TextToSpeechPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 语音合成的实现（{@code #333} TEXT_TO_SPEECH_NODE）—— 走 {@code /audio/speech}。
 *
 * <p>★ 空字节当失败：上游回 200 但一个字节都没有，说明它没真的合成（常见于 voice 名字不认识时 某些实现会静默返回空体）。把它当成功会让节点记录成"已生成"，而下游拿到的是 0
 * 字节文件。
 */
@Component
class TextToSpeechAdapter implements TextToSpeechPort {

  private final MultimodalTargets targets;
  private final UpstreamGateway gateway;

  TextToSpeechAdapter(MultimodalTargets targets, UpstreamGateway gateway) {
    this.targets = targets;
    this.gateway = gateway;
  }

  @Override
  public Optional<Speech> synthesize(UUID modelId, String text, String voice) {
    return targets
        .resolve(modelId, ModelKind.TTS)
        .map(
            target -> {
              byte[] audio =
                  gateway.speech(
                      target.baseModel(),
                      text,
                      voice,
                      "mp3",
                      target.apiBase(),
                      target.apiKey(),
                      target.authStyle());
              if (audio == null || audio.length == 0) {
                throw new BusinessException(
                    ErrorCode.UPSTREAM_UNAVAILABLE,
                    "语音合成返回了 0 字节音频",
                    "上游接受了请求但没给出音频 —— 先确认 voice 名字它认得（不同实现的取值不一样）");
              }
              return new Speech(audio, "mp3");
            });
  }
}
