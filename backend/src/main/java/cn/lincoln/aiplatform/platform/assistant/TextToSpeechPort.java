package cn.lincoln.aiplatform.platform.assistant;

import java.util.Optional;
import java.util.UUID;

/**
 * 语音合成能力：文本进、音频字节出。
 *
 * <p>★ 返回字节 + 格式，不返回文件路径：落盘位置（对象存储 / 会话目录）是调用方的事， 端口这一层不该知道 {@code backend/data/files} 存在。
 *
 * <p>★ {@code empty} 同其它媒体端口：模型调不了，不是"合成出了静音"。
 */
public interface TextToSpeechPort {

  Optional<Speech> synthesize(UUID modelId, String text, String voice);

  /**
   * 合成结果。
   *
   * @param audio 音频字节
   * @param format 容器格式（{@code mp3} / {@code wav} 等）；调用方要按它决定扩展名与 Content-Type
   */
  record Speech(byte[] audio, String format) {}
}
