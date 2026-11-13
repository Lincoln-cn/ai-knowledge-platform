package cn.lincoln.aiplatform.platform.assistant;

import java.util.Optional;
import java.util.UUID;

/**
 * 图像生成能力：给一句提示词，拿回图片字节。
 *
 * <p>★ 返回字节而不是 URL：上游回的 URL 是它的名字空间，我们既不知道会不会过期， 也不知道内网部署时浏览器能不能访问到它。要 URL 的调用方自己决定要不要落盘 / 转存。
 *
 * <p>★ {@code empty} 与 {@link ImageUnderstandPort} 同义：模型调不了，不是"生成了空图"。
 */
public interface ImageGeneratePort {

  Optional<Generated> generate(UUID modelId, String prompt, String size);

  /**
   * 生成的图片。
   *
   * @param bytes 图片字节
   * @param mimeType 图片类型；上游没有说明时为 {@code image/png}（拿不确定的类型去存盘， 最坏的结果是浏览器打不开那张图，而文件在磁盘上"看起来正常"）
   */
  record Generated(byte[] bytes, String mimeType) {}
}
