package cn.lincoln.aiplatform.platform.assistant;

import java.util.Optional;
import java.util.UUID;

/**
 * 语音识别能力：音频进、文本出。
 *
 * <p>★ {@code empty} 同其它媒体端口：模型调不了，不是"识别出空文本"。 后者（上游回了 200 但 {@code text} 是空串）会作为 {@code
 * Optional.of("")} 返回 —— 一段没人说话的录音确实应该得到空文本，把它报成"模型不可用"会让人去查配置。
 */
public interface SpeechToTextPort {

  /**
   * @param modelId 模型目录里的模型主键
   * @param audio 音频字节
   * @param filename 原文件名（上游靠扩展名判容器格式；缺失时调用方应给一个带扩展名的名字）
   */
  Optional<String> transcribe(UUID modelId, byte[] audio, String filename);
}
