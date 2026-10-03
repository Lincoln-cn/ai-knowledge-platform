package cn.lincoln.aiplatform.knowledge.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OCR 配置（{@code application.yml} 的 {@code app.ocr.*}）—— 第 9 周 · 0.3.1。
 *
 * <h3>★ 为什么这些必须是<strong>配置</strong>而不是"环境里恰好有"</h3>
 *
 * <p>本项目的部署形态是<strong>单客户内网私有化</strong>（{@code ROADMAP §九}），交付时大概率<strong>离线</strong>。 而 OCR
 * 引擎（{@code tesseract} + 语言包）是<strong>系统级资产</strong>，不像 jar 那样跟着构建走 —— 于是"这台机器上能不能
 * OCR"极容易变成一个<strong>偶然后果</strong>：
 *
 * <ul>
 *   <li>开发机上有人 {@code scoop install} 过 ⇒ 能用
 *   <li>换台机器/换个镜像忘了装 ⇒ <strong>不报错</strong>，只是扫描件解析出空，
 *       用户看到的是「可能是扫描件」那句通用文案，<strong>看不出是引擎没装</strong>
 * </ul>
 *
 * <p>⇒ 三件事一起做，才能把它从"偶然后果"变成"显式事实"： ① <strong>路径显式配置</strong>（{@code 12 §11.2} 的坑 ① 早就写了：tessdata
 * 路径必须显式给）； ② <strong>启动时探测并把结论暴露出来</strong>（{@link OcrEngine} + {@code /actuator/health}）； ③
 * <strong>缺失时给可读原因与下一步</strong>（不是静默降级）。
 *
 * @param enabled 是否启用 OCR。★ 关掉后扫描件会落在"解析结果为空"（可读原因会说清是配置关的）
 * @param tesseractPath tesseract 可执行文件所在目录或完整路径；<b>留空 = 走 PATH</b> （⚠️ 留空就回到"环境里恰好有"，生产/离线交付请显式给）
 * @param tessdataPath 语言包目录；<b>留空 = 依次尝试 {@code TESSDATA_PREFIX} 环境变量</b>。 ★ 离线交付时这是最该显式钉住的一项 ——
 *     镜像里语言包装在哪，只有部署者知道
 * @param languages Tesseract 语言参数（{@code chi_sim+eng}）。★ 每个语言都要有对应的 {@code
 *     <lang>.traineddata}，缺一个中文就会退化成方块或乱码，且<strong>不报错</strong>
 * @param dpi 光栅化 DPI。{@code 12 §11.3}/{@code §11.4} 定的下限是 <b>300</b>，调低会明显掉识别率
 * @param timeoutSeconds 单页 OCR 超时。★ 必须有上限：一页 A4 CPU 约 1–5 秒（{@code 12 §11.1}），
 *     但没有上限时一张异常大的图会把后台任务挂死
 * @param preprocess 是否开启 Tika 的图像预处理（deskew 等）。{@code 12 §11.4} 要求 deskew 必开
 */
@ConfigurationProperties(prefix = "app.ocr")
public record OcrProperties(
    boolean enabled,
    String tesseractPath,
    String tessdataPath,
    String languages,
    int dpi,
    int timeoutSeconds,
    boolean preprocess) {

  /** 语言列表（拆 {@code +}，去掉空白项）。 */
  public java.util.List<String> languageList() {
    if (languages == null || languages.isBlank()) {
      return java.util.List.of();
    }
    return java.util.Arrays.stream(languages.split("\\+"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }
}
