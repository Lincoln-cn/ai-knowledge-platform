package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.application.EntityExtractor;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * HanLP 1.x 实现（0.6.1 的 A 方案）。
 *
 * <h2>★★ 关键读数：HanLP 1.x 自己的 NER 在制度文本上**不能直接用**</h2>
 *
 * <p>2026-11-11 用真语料（`.build/rerank/candidates.jsonl` 的 6 段、每段约 410 字）实测， 打开 {@code
 * enableOrganizationRecognize} + {@code enablePlaceRecognize} + {@code enableNameRecognize} 之后：
 *
 * <pre>
 *   财务资产部/nt   ← ✅ 对
 *   生产设备/nt     ← ❌ 不是机构
 *   对生产设备/nt   ← ❌ 连"对"都吃进名字里
 *   应于/nr         ← ❌ 时间介词被当成人名
 *   须将/nr         ← ❌ 同上
 *   应在/nr         ← ❌ 同上
 * </pre>
 *
 * <p>所以这个类**不用 HanLP 的 NER**，只用它的**分词与词性**：
 *
 * <ol>
 *   <li>HanLP 负责切词（"財務資產部"能切成一个词，这是它真正强的地方）；
 *   <li>**机构判定交给后缀规则** —— 切出来的词必须以机构后缀结尾（部/局/处/中心…） 才算 {@code ORG}。`生产设备` 不满足，`财务资产部` 满足；
 *   <li>**人名不抽**（{@code supportedTypes} 里没有 PERSON）—— 制度文档里几乎不出现 真实人名，而 HanLP 把"应于/须将/应在"当人名的误报率是
 *       100%（三处全错）。 真要抽人名，等有真实标注数据再上模型（B 方案）。
 * </ol>
 *
 * <p>★ 这条判据值得单独记：**"库说自己支持 NER" 与 "它在你的领域里有没有用" 是两件事** —— 后者只能拿真语料试出来，而且要**逐条看它错在哪**，不能只看"抽了几个"。
 *
 * <h2>线程安全</h2>
 *
 * <p>{@link Segment} 实例不是线程安全的；这里用 {@link ThreadLocal} 每个线程一份， 且**不挂任何自定义词典**（词典走 {@link
 * EntityExtractor} 的规则层，见下的"为什么 不灌 CustomDictionary"）。
 *
 * <p>★ 为什么不灌 {@code CustomDictionary}：上游 javadoc 明写"动态增删改不是线程安全的"， 而我们的字典是 per-库（多个库并发问答）。灌进全局词典会让
 * A 库的词影响 B 库的切分， 那是**静默的跨库污染**，比少抽几个实体糟得多。词典在这里只用于**判定**（一个切出来 的词是不是已登记实体），不参与切分。
 */
@Component
public class HanlpEntityRecognizer implements EntityRecognizer {

  /** ★ `supportedTypes` 里**没有 PERSON**：见类注释第 3 条。 也没有 TERM（引号里的术语由规则层给）。 */
  private static final List<String> TYPES = List.of("ORG", "DOC");

  private final Segment segment;

  /** 后缀表来自规则层，保证两个实现用同一份"什么叫机构"的定义。 */
  private final List<String> orgSuffixes;

  private final Pattern docPattern = Pattern.compile("《([^《》]{2,40})》");

  public HanlpEntityRecognizer(List<String> orgSuffixes) {
    if (orgSuffixes.isEmpty()) {
      throw new IllegalArgumentException("机构后缀为空：识别器会一个实体都抽不出来");
    }
    this.orgSuffixes =
        orgSuffixes.stream().sorted((a, b) -> Integer.compare(b.length(), a.length())).toList();
    // ★★ 必须开 enableOrganizationRecognize：不开的话 HanLP 会把「财务资产部」切成
    // `财务/n 资产/n 部/q`（"部"当量词），一个机构都切不出来（实测）。
    // 但开了它又会把「生产设备」标成 nt —— 所以下面还要用后缀表再过滤一遍。
    // 这两件事缺一不可：只开 NER ⇒ 误报；只靠分词 ⇒ 一个都抽不到。
    this.segment = HanLP.newSegment().enableOrganizationRecognize(true);
  }

  @Override
  public List<Recognized> recognize(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    Set<String> seen = new LinkedHashSet<>();
    List<Recognized> out = new ArrayList<>();

    // ① 机构：HanLP 切词 + 后缀判定
    for (Term term : segment.seg(text)) {
      String word = term.word.strip();
      if (word.length() < 3) {
        continue; // "部"单独一个词不是机构
      }
      if (word.chars().allMatch(Character::isLetterOrDigit) && !containsHan(word)) {
        continue;
      }
      if (endsWithOrgSuffix(word) && seen.add("ORG:" + word)) {
        // ★ 规则判定给固定置信度，不假装是模型概率
        out.add(new Recognized("ORG", word, 0.8));
      }
    }

    // ② 制度文书名：书名号是最可靠的信号，不用分词
    Matcher matcher = docPattern.matcher(text);
    while (matcher.find()) {
      String name = matcher.group(1).strip();
      if (!name.isEmpty() && seen.add("DOC:" + name)) {
        out.add(new Recognized("DOC", name, 0.95));
      }
    }
    return List.copyOf(out);
  }

  /**
   * ★ 关系不在这里给：HanLP 1.x 没有关系抽取。
   *
   * <p>返回空列表表示"这一层抽不出"，不是"这段没有关系"。关系由规则层 （标记词 + 两端在实体表里）补 —— 见 {@link EntityRecognizer} 的接口注释。
   */
  @Override
  public List<Relation> relations(String text) {
    return List.of();
  }

  @Override
  public List<String> supportedTypes() {
    return TYPES;
  }

  @Override
  public String name() {
    return "hanlp-1.8.6";
  }

  private boolean endsWithOrgSuffix(String word) {
    for (String suffix : orgSuffixes) {
      if (word.endsWith(suffix) && word.length() > suffix.length() + 1) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsHan(String s) {
    return s.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
  }

  /** 让 {@link EntityExtractor} 的默认后缀表成为两个实现共享的单一来源。 */
  public static List<String> defaultSuffixes() {
    return List.of("部", "局", "处", "科", "室", "中心", "集团", "公司", "委员会", "办公室");
  }

  /** 便于测试：不依赖 Spring 的构造。 */
  public static HanlpEntityRecognizer withDefaults() {
    return new HanlpEntityRecognizer(defaultSuffixes());
  }

  /** 未使用但保留：{@link Nature} 的常量在编译期被 javadoc 引用，避免 spotless 判未使用导入。 */
  static Nature unusedMarker() {
    return Nature.n;
  }
}
