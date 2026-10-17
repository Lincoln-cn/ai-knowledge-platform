package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code #72} 判据 4 与"出网白名单"的<strong>缺席断言</strong> —— 钉住"当前没做这件事"。
 *
 * <h3>它为什么必须存在（而不是"等做了再写测试"）</h3>
 *
 * <p>本批按 {@code ROADMAP §11.7.2} 的裁定把 {@code #72} 走了「留缝」： <strong>调用侧闸门做实体</strong>（{@code
 * ChatService} + {@code ModelEgressPort}，见 {@code ChatEgressGateTest} / {@code
 * ModelEgressAdapterTest}）， 而<strong>出网白名单与审计留痕只记账</strong>。
 *
 * <p>★ 风险就在这一步：设计册 §6.5 一旦写了「出口方向已落地」，
 * 后来的人会读成<strong>三层都做了</strong>。而"没做"这件事<strong>没有任何读数</strong> —— 它不报错、不影响问答、不留日志。⇒
 * 用一条<strong>能红的</strong>断言把它钉住： <strong>哪天真做了，它必须红</strong>（那时删掉对应条目并同步 §6.5 的边界表）。
 *
 * <h3>★★ 这条判据的形状：按"落点指纹"扫源码，而不是读文档</h3>
 *
 * <p>读文档只能证明"文档这么写"，证明不了"代码里没有"。所以这里扫 {@code backend/src/main/java/**} 的 Java
 * 源码，用三组<strong>具体到名字</strong>的指纹判断：
 *
 * <ol>
 *   <li><b>出网白名单</b>：出站前的域名/IP 允许列表（{@code 06 §3.0.2} 给工具调用设计的那一套， 要求"同一套机制，不要两套"）——
 *       指纹：白名单/允许列表类的名字；
 *   <li><b>审计留痕</b>：{@code #72} 判据 4 要的四要素落库（谁 / 哪个应用 / 最高密级 / 目标模型）；
 *   <li><b>模型级 ceiling 列</b>：{@code ai_model} 上那一列（现在是平台级配置）。
 * </ol>
 *
 * <p>★ 每一条都写明"命中什么就算做了" —— 这样它既不会因为无关代码误红，也不会因为改个名字就静默失效。
 */
class EgressGateAbsenceTest {

  /** 生产源码根（相对模块目录，★ 用 {@code user.dir} 是因为 surefire 的工作目录就是 {@code backend/}）。 */
  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  /**
   * ★ 出网白名单的指纹：任何一处出现"出站域名/IP 允许列表"的实现。
   *
   * <p>★ 为什么这几个名字：{@code 06 §3.0.2} 给工具调用设计的是"工具级权限 + 出网白名单 + 调用审计"， 而 {@code #72}
   * 要求模型调用侧<strong>复用同一套</strong>。所以真做的时候， 名字里几乎必然会带 outbound / egress + allow(ed)List / whitelist。
   */
  private static final List<String> OUTBOUND_ALLOWLIST_MARKERS =
      List.of("outboundAllowlist", "egressAllowlist", "outboundWhitelist", "egressWhitelist");

  /**
   * ★ 审计留痕的指纹：{@code #72} 判据 4 那四要素被写进审计表/审计事件。
   *
   * <p>★ 用"审计事件的构造"而不是泛泛的 {@code audit} 字样 —— 后者在权限模块里到处都有， 会把这条判据变成一个噪声源（那是本仓 {@code #87}
   * 记过的"影子缺口"反面：误红）。
   */
  private static final List<String> EGRESS_AUDIT_MARKERS =
      List.of("EgressDenied", "egressDenied", "recordEgressRefusal");

  @Test
  void outboundAllowlistIsStillMissing() throws IOException {
    List<String> hits = occurrences(OUTBOUND_ALLOWLIST_MARKERS);
    assertThat(hits)
        .as(
            """
            ★★ #72：出网白名单**本批没做**，而这条断言就是它的读数。
            %s
            ⇒ 如果这里红了，说明有人已经把出网白名单落进了代码 —— 那是对的，
              但**必须同时**做两件事（否则口径与读数会各自漂）：
                ① 删掉本方法（或把它改成"已落地"的正向断言）；
                ② 同步 `13 §6.5` 边界表里『出网侧 ❌ 未做』那一行。
            """,
            hits)
        .isEmpty();
  }

  @Test
  void egressAuditTrailIsStillMissing() throws IOException {
    List<String> hits = occurrences(EGRESS_AUDIT_MARKERS);
    assertThat(hits)
        .as(
            """
            ★★ #72 判据 4：被拒绝的那次调用**还没有审计留痕**（现在只有一条 WARN 日志）。
            %s
            ⇒ 红了说明审计已经落了 —— 请同时删掉本方法并同步 `13 §6.5` 边界表那一行。
            """,
            hits)
        .isEmpty();
  }

  /**
   * ★ 模型级 ceiling 列还只在**平台级配置**里。
   *
   * <p>判据：{@code ai_model} 的实体上不该出现 classification/ceiling 类字段 ——
   * 有了它就意味着那一列已经加进库里（那是一条迁移），口径的粒度也随之从"平台"变成"模型"。
   */
  @Test
  void modelLevelCeilingColumnIsStillMissing() throws IOException {
    Path model = MAIN_SOURCES.resolve("cn/lincoln/aiplatform/modelcatalog/domain/Model.java");
    assertThat(model).as("★ 前置：Model 实体必须还在这个位置（挪了就该更新本断言）").isRegularFile();
    String source = Files.readString(model, StandardCharsets.UTF_8);
    List<String> hits = new ArrayList<>();
    for (String marker :
        List.of("maxClassification", "allowedClassification", "classificationCeiling")) {
      if (source.contains(marker)) {
        hits.add("Model.java 含 `" + marker + "`");
      }
    }
    assertThat(hits)
        .as(
            """
            ★★ #72：模型级 `allowed ceiling` 列**本批没加**（现在是平台级配置 `app.egress.external-api-ceiling`）。
            %s
            ⇒ 红了说明那一列已经进了实体（迁移也随之落了）—— 请删掉本方法并同步 `13 §6.5` 边界表那一行。
            """,
            hits)
        .isEmpty();
  }

  /** 在全部生产源码里找这些标识符，返回 {@code 文件:行} 形式的命中（★ 带行号，红了能直接去看）。 */
  private static List<String> occurrences(List<String> markers) throws IOException {
    assertThat(MAIN_SOURCES).as("★ 前置：工作目录应当是 backend/（surefire 的默认）").isDirectory();
    List<String> hits = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          // ★ 跳过注释行：注释里**提到**一个名字不等于实现了它（否则口径文档式注释会让这条判据误红）
          String trimmed = line.trim();
          if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            continue;
          }
          for (String marker : markers) {
            if (line.contains(marker)) {
              hits.add(file.getFileName() + ":" + (i + 1) + " → " + marker);
            }
          }
        }
      }
    }
    return hits;
  }
}
