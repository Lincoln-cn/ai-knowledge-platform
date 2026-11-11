package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.infrastructure.HanlpEntityRecognizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在 30 段人工基线（{@code backend/tools/ner_testset.jsonl}）上量 HanLP 的机构名抽取质量。
 *
 * <p>口径与 {@code backend/tools/ner_baseline.py} 完全一致 —— <b>精确匹配</b>：抽出来的名字必须与人
 * 工基线里的某一项<b>逐字相等</b>才算对。不用「包含」判定，因为那会把 {@code 应配合信息技术部} 判成命中 {@code 信息技术部}，恰好掩盖"吞并前后文"这个最主要的缺陷。
 *
 * <p>这个类默认 {@code @Disabled}：它依赖仓库外的模型与人工基线文件，且跑一次要几秒； 想复现读数时把注解去掉，跑完再改回去。
 *
 * <p>读数（2026-11-11，30 段 / 38 个实体 / 12 个名字）：
 *
 * <pre>
 * 规则版 EntityExtractor : TP 27 FP 72 FN 11 ⇒ 精度 27.3% 召回 71.1% F1 39.4%
 * ONNX BERT (CCKS 2020)  : TP 36 FP 15 FN  2 ⇒ 精度 70.6% 召回 94.7% F1 80.9%
 * HanLP（本类）          : 见运行输出
 * </pre>
 */
@Disabled("äººå·¥åºçº¿å¨ä»åºå¤ï¼ä¸è¿æ¯éå°ºä¸æ¯åå½ï¼è¦è¯»æ°æ¶å»ææ¬è¡")
class HanlpNerAccuracyTest {

  private static final Path TESTSET =
      Path.of("..", "backend", "tools", "ner_testset.jsonl").normalize();

  /** 只抽组织名的识别器：后缀表用内置默认（与生产上 builtin 打底同源）。 */
  private static EntityRecognizer recognizer() {
    return new HanlpEntityRecognizer(JdbcDefaultSuffixes.of());
  }

  @Test
  @DisplayName("HanLP 在 30 段人工基线上的精确率与召回率")
  void measureOnHumanBaseline() throws IOException {
    Path path = Files.exists(TESTSET) ? TESTSET : Path.of("tools", "ner_testset.jsonl");
    assertThat(Files.exists(path)).as("找不到人工基线：%s", path).isTrue();

    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    EntityRecognizer ner = recognizer();

    int truePositive = 0;
    int falsePositive = 0;
    int falseNegative = 0;
    List<String> misses = new ArrayList<>();
    List<String> extras = new ArrayList<>();

    JsonMapper mapper = JsonMapper.builder().build();
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      JsonNode row = mapper.readTree(line);
      String doc = row.path("doc").asText();
      String text = row.path("text").asText();
      Set<String> human = new LinkedHashSet<>();
      row.path("expected").forEach(n -> human.add(n.asText()));

      Set<String> got = new LinkedHashSet<>();
      for (EntityRecognizer.Recognized one : ner.recognize(null, text)) {
        if ("ORG".equals(one.type())) {
          got.add(one.name());
        }
      }

      for (String name : got) {
        if (human.contains(name)) {
          truePositive++;
        } else {
          falsePositive++;
          extras.add(doc + " :: " + name);
        }
      }
      for (String name : human) {
        if (!got.contains(name)) {
          falseNegative++;
          misses.add(doc + " :: " + name);
        }
      }
    }

    double precision =
        truePositive + falsePositive == 0
            ? 0
            : 100.0 * truePositive / (truePositive + falsePositive);
    double recall =
        truePositive + falseNegative == 0
            ? 0
            : 100.0 * truePositive / (truePositive + falseNegative);
    double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);

    System.out.printf(
        "%n[HanLP 读数] TP %d FP %d FN %d ⇒ 精度 %.1f%% 召回 %.1f%% F1 %.1f%%%n",
        truePositive, falsePositive, falseNegative, precision, recall, f1);
    System.out.println("[HanLP 误抽 " + extras.size() + " 条]");
    extras.forEach(x -> System.out.println("  + " + x));
    System.out.println("[HanLP 漏抽 " + misses.size() + " 条]");
    misses.forEach(x -> System.out.println("  - " + x));

    assertThat(truePositive).as("连一个都没抽对，说明识别器没跑起来").isPositive();
  }

  /** 内置默认词表 —— 与 {@code JdbcEntityDictionaryAdapter.builtin()} 同源。 */
  private static final class JdbcDefaultSuffixes {
    private JdbcDefaultSuffixes() {}

    static List<String> of() {
      return List.of("部", "处", "科", "室", "中心", "公司", "集团", "局", "委员会", "领导小组");
    }

    static java.util.Map<String, String> marks() {
      return java.util.Map.of(
          "隶属", "BELONGS_TO",
          "下属", "BELONGS_TO",
          "归口", "BELONGS_TO",
          "下设", "CONTAINS",
          "包括", "CONTAINS",
          "负责", "RESPONSIBLE_FOR",
          "主管", "RESPONSIBLE_FOR",
          "牵头", "RESPONSIBLE_FOR",
          "是指", "DEFINES",
          "简称", "DEFINES");
    }
  }
}
