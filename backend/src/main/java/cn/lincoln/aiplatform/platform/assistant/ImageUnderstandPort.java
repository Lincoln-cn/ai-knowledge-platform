package cn.lincoln.aiplatform.platform.assistant;

import java.util.Optional;
import java.util.UUID;

/**
 * 视觉理解能力：给一张图片和一句问题，拿回一段文字。
 *
 * <p>★ 它走的是<strong>对话模型</strong>（{@code kind=CHAT}），不是"视觉模型"：OpenAI 兼容协议里 视觉输入只是 {@code messages} 里
 * content 变成数组。单开一种模型种类会逼人回答"既能对话又能看图的算哪种"， 而那个答案不该有二义。
 *
 * <p>★ {@code empty} 的含义是"这个模型调不了"（不存在 / 未加载 / 种类不对），<strong>不是</strong>"图里没内容"。
 * 两种情况都要能分辨：前者是配置问题，后者是业务结果。
 *
 * @param modelId 模型目录里的模型主键（节点配置里选的那个）
 * @param image 图片字节
 * @param mimeType 图片类型，如 {@code image/png}；null 时按 {@code image/png} 处理
 * @param question 问它什么
 */
public interface ImageUnderstandPort {

  Optional<String> describe(UUID modelId, byte[] image, String mimeType, String question);
}
