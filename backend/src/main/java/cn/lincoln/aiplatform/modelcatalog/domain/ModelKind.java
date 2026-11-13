package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 模型种类。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code CHAT / EMBEDDING / TTS / STT /
 * IMAGE}），标识符 = 线上值 = 库存值三者同名。展示文案不进标识符、不进线上值、不进库。
 *
 * <p>它决定两件不可混的事：对话模型带 {@code contextWindow}、向量模型带 {@code dimension}， 两者互斥且不能互换（向量维度与向量表绑定，改则需全量重灌）。
 *
 * <p>★ 多模态三种（{@link #TTS} / {@link #STT} / {@link #IMAGE}）既没有上下文窗口也不产出向量，
 * 两列都必须为空。它们存在的理由不是"分类好看"，而是让三处判断有唯一依据：
 *
 * <ol>
 *   <li>创建时校验什么 —— 多模态不接受 {@code dimension} / {@code contextWindow} / {@code thinking} / {@code
 *       tools}；
 *   <li>探测探什么 —— 对话模型探思考与工具，向量模型实测维度，多模态不探（它没有 {@code messages} 语义， 拿它去调 {@code /chat/completions}
 *       只会得到一个 400，然后被当成"模型不可用"；
 *   <li>界面显示哪一列 —— 把「改维度需全量重灌」的警示挂到 TTS 模型上是纯粹的噪音。
 * </ol>
 *
 * <p>★ 视觉理解（{@code IMAGE_UNDERSTAND_NODE}）<strong>不在</strong>这里：它用的就是对话模型， 只是 {@code messages} 里的
 * content 是带图片的数组。给它单开一种"视觉模型"会逼着人回答 "一个既能对话又能看图的模型算哪种"，而答案本来就不该有二义。
 */
public enum ModelKind {
  /** 对话模型：带上下文窗口，可声明思考模式与工具调用。 */
  CHAT,
  /** 向量模型：带实测维度，与向量表绑定。 */
  EMBEDDING,
  /** 语音合成（text-to-speech）：文本进、音频出。 */
  TTS,
  /** 语音识别（speech-to-text）：音频进、文本出。 */
  STT,
  /** 图像生成：提示词进、图片出。 */
  IMAGE
}
