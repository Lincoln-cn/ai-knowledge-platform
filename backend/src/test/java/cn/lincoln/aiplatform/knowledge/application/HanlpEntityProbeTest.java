package cn.lincoln.aiplatform.knowledge.application;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.Segment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一次性探针：拿真语料看 HanLP 1.x 能抽出什么（不进门禁，跑完把 {@code @Disabled} 加回去）。
 *
 * <p>★ 它不复用 {@link EntityExtractor}：那个类是规则版，这里要看的是 HanLP 自己的 结果，两者混在一起就说不清"哪一部分是规则抽的"。
 *
 * <p>★ 第一版用字符串切分包 JSON，抽出来 0 段 —— 已知的教训：**手工切 JSON 一定错**， 换成 Jackson（项目已是 Jackson 3）。
 *
 * <p>数据源 `.build/rerank/candidates.jsonl`：195 条问题 × 每条 20 个候选段，段正文 约 406 字，是真语料且不依赖数据库在跑。
 */
@org.junit.jupiter.api.Disabled("一次性探针：只在重新评估 HanLP 质量时手动跑，不进日常门禁")
class HanlpEntityProbeTest {

  private static final Path CANDIDATES = Path.of("..", ".build", "rerank", "candidates.jsonl");

  @Test
  void printWhatHanlpFindsOnRealSegments() throws IOException {
    if (!Files.exists(CANDIDATES)) {
      System.out.println("[探针] 找不到 " + CANDIDATES.toAbsolutePath() + "，跳过");
      return;
    }

    JsonMapper json = JsonMapper.builder().build();
    List<String> segments = new ArrayList<>();
    for (String line : Files.readAllLines(CANDIDATES, StandardCharsets.UTF_8)) {
      if (segments.size() >= 6) {
        break;
      }
      JsonNode row = json.readTree(line);
      for (JsonNode item : row.path("items")) {
        if (segments.size() >= 6) {
          break;
        }
        String content = item.path("content").asText("");
        if (!content.isBlank()) {
          segments.add(content);
        }
      }
    }

    System.out.println("=== 探针段数：" + segments.size() + " ===");

    Segment withOrg = HanLP.newSegment().enableOrganizationRecognize(true);
    Segment withAll =
        HanLP.newSegment().enableOrganizationRecognize(true).enablePlaceRecognize(true);
    Segment withPerson =
        HanLP.newSegment()
            .enableOrganizationRecognize(true)
            .enablePlaceRecognize(true)
            .enableNameRecognize(true);

    int printed = 0;
    for (String seg : segments) {
      if (printed >= 3) {
        break;
      }
      printed++;
      System.out.println(
          "\n--- 段 "
              + printed
              + "（"
              + seg.length()
              + " 字）："
              + seg.substring(0, Math.min(50, seg.length()))
              + "…");
      System.out.println("  [org]       " + named(seg, withOrg));
      System.out.println("  [org+place] " + named(seg, withAll));
      System.out.println("  [org+place+person] " + named(seg, withPerson));
    }
  }

  /** 只印 nt（机构）/ ns（地名）/ nr（人名）三类。 */
  private static String named(String text, Segment segment) {
    String out =
        segment.seg(text).stream()
            .filter(
                t ->
                    t.nature != null
                        && (t.nature.toString().equals("nt")
                            || t.nature.toString().equals("ns")
                            || t.nature.toString().equals("nr")))
            .map(t -> t.word + "/" + t.nature)
            .collect(Collectors.joining(" "));
    return out.isEmpty() ? "（无）" : out;
  }
}
