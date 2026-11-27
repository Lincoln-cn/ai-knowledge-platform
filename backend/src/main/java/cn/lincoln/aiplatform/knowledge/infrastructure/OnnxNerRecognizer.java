package cn.lincoln.aiplatform.knowledge.infrastructure;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import cn.lincoln.aiplatform.knowledge.application.EntityExtractor;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 基于 ONNX BERT 的中文实体识别。
 *
 * <h3>它比 HanLP 好在哪（2026-11-11 实测，同一套 30 段人工验证集）</h3>
 *
 * <pre>
 *   规则版      精度  27.3%  召回 71.1%  F1 39.4%
 *   HanLP 1.8.6 精度  48.7%  召回 50.0%  F1 49.4%
 *   ONNX BERT   精度 100.0%  召回 97.4%  F1 98.7%（做过碎片收敛）
 * </pre>
 *
 * <p>HanLP 的错法是「分词把机构名切碎」：{@code 采购供应部} 被切成 {@code 采购} + {@code 供应部}， 于是 {@code 供应部}
 * 变误抽、真机构名变漏抽，一次切错在精确率与召回率上各记一笔 （FP 20 与 FN 19 几乎相等，就是这个形状）。BERT 按字级别打标签，没有这个前提问题。
 *
 * <h3>★ 标签表必须从 config.json 读，不能硬编码</h3>
 *
 * <p>第一次写这个类时标签是写死的，写完自查发现 {@code labels()} 返回空、 一个实体都抽不出来 —— 能编译、能过「返回空」的测试、名字叫 OnnxNerRecognizer，
 * 但功能是零。这是本项目一直在批的 silent-failure 形状，所以当时删了文件而不是提交。
 *
 * <p>现在标签来自模型自带的 {@code config.json} 的 {@code id2label}： 顺序是导出时定死的，写死一份必然在换模型时错位，而错位不会抛异常，只会静默抽错。
 *
 * <h3>只认机构与职位人名</h3>
 *
 * <p>29 类标签里与组织相关的有三类：{@code organization} / {@code company} / {@code government}。
 * 地名、时间、邮箱、手机号都在标签里，但本项目的实体库只服务「制度文档里的责任主体」， 抽地名只会给 {@code kb_entity} 塞噪声。{@link #supportedTypes()}
 * 如实声明支持哪些类型。
 *
 * <p>★ 2026-11-11 加了 PERSON（{@link #PERSON_LABELS}）：同一天 {@code HanlpEntityRecognizer} 也加了。**两个实现的
 * {@code supportedTypes()} 必须一致** —— 否则换一个 bean 行为就变， 而调用方是按接口声明写代码的（见 {@code §11.36.3} 第 ② 条纪律）。
 *
 * <h3>关系交给规则层（不在模型里抽）</h3>
 *
 * <p>模型只认实体；关系走标记词规则（{@code EntityExtractor}），与 {@code HanlpEntityRecognizer} 同一套判据。
 *
 * <p>★ 2026-11-27（`#368`）：这里以前直接返回空列表，理由写的是"关系靠规则层"， 可规则层只被 {@code HanlpEntityRecognizer} 组合过 —— 而
 * {@code application.yml} 的默认值是 onnx ⇒ <b>关系一条都抽不出来</b>， {@code kb_relation} 恒空，关系召回那一路静默失效。 这与
 * 2026-11-11 HanLP 那次是同一个坑（见 {@code HanlpEntityRecognizer#relations} 的注释）： "抽不出"与"没有关系"读数完全一样。
 *
 * <h3>碎片收敛</h3>
 *
 * <p>BERT 按字打标签，同一串字可能给出重叠的 span（{@code 生} / {@code 生产} / {@code 生产制造部}）。 这里保留被更长名字包含的最长者（{@code
 * 生产制造部}），丢掉它的前缀碎片。 这一步在离线探针里让精度从 70.6% 升到 100.0% —— 不是可选项。
 */
public class OnnxNerRecognizer implements EntityRecognizer {

  private static final Logger LOG = LoggerFactory.getLogger(OnnxNerRecognizer.class);

  /** 与 {@code JdbcEntityDictionaryAdapter.builtin()} 同源的后缀表。收敛碎片时用。 */
  static final List<String> DEFAULT_ORG_SUFFIXES =
      List.of("部", "处", "科", "室", "中心", "公司", "集团", "局", "委员会", "领导小组");

  /** 与 {@link #DEFAULT_ORG_SUFFIXES} 同长度；只收以这些结尾的候选。 */
  static final Set<String> ORG_LABELS = Set.of("organization", "company", "government");

  /**
   * 人名类标签（CCKS 2020 里只有 {@code name} 这一组）。
   *
   * <p>★★ 2026-11-11 加（`§11.36.1` 的 B 项）：同一天给 {@code HanlpEntityRecognizer} 加了 PERSON，
   * 而这里没加的话，**同一个接口的两个实现能力不同** —— 换一个 bean 行为就变，而调用方按 {@link #supportedTypes()} 写代码，读到的却是两套答案。
   *
   * <p>★ 与 HanLP 那条路的关键差别：HanLP 走**规则**（名字 + 职务词后缀），这里走**模型**（B-I-name）。 模型认的是"看起来像人名"，不管后面有没有职务词
   * —— 所以置信度给低一档（0.65）， 并且在 {@code compact} 之后仍按后缀表再收一次（人名没有后缀，只剩长度约束）。
   */
  static final Set<String> PERSON_LABELS = Set.of("name");

  /**
   * 开头的虚词不当实体开头：「应配合财务资产部」不该整体入库。
   *
   * <p>★ 这是「排除法」而不是「白名单」—— 只排掉实测见过的高频噪声前缀， 不试图穷举所有虚词（那会漏掉真机构名）。所以它有已知的漏网：
   * 前缀表里没有的字（比如「经」「按」之外的动词）仍会被带进 span。 判据 {@code stripsVirtualWordPrefix} 用的是实测真出现过的那一类，不是构想的例子。
   */
  private static final String VIRTUAL_PREFIX = "的了是在和与及或对为把被将由按据须经报及以配应";

  private final Path modelDir;
  private final int threads;
  private final List<String> orgSuffixes;

  /**
   * 关系抽取的规则层（`#368`）。
   *
   * <p>★ 它<b>不允许为空</b>：构造时若调用方没传就自建一份（内置机构后缀 + 内置标记词）。 兜底在这里而不是在调用方，是为了让"忘了传"的后果停在"用了默认词表"，
   * 而不是退化成"静默不抽关系" —— 后者不会有任何异常，只会在关系表里少一批行。
   */
  private final EntityExtractor relationExtractor;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private volatile OrtSession session;
  private volatile HuggingFaceTokenizer tokenizer;
  private volatile List<String> labels;
  private volatile String loadFailure;

  public OnnxNerRecognizer(Path modelDir, int threads, List<String> orgSuffixes) {
    this(modelDir, threads, orgSuffixes, null);
  }

  /**
   * @param relationExtractor 规则层；{@code null} 表示用内置词表自建一份
   */
  public OnnxNerRecognizer(
      Path modelDir, int threads, List<String> orgSuffixes, EntityExtractor relationExtractor) {
    this.modelDir = resolveModelDir(modelDir);
    this.threads = Math.max(1, threads);
    this.orgSuffixes = List.copyOf(orgSuffixes);
    if (this.orgSuffixes.isEmpty()) {
      throw new IllegalArgumentException("机构后缀为空：收敛层会把所有候选都丢掉");
    }
    this.relationExtractor =
        relationExtractor != null
            ? relationExtractor
            : new EntityExtractor(
                new EntityExtractor.Dictionary(
                    this.orgSuffixes,
                    List.of(),
                    JdbcEntityDictionaryAdapter.builtin().relationMarks()));
  }

  public OnnxNerRecognizer(Path modelDir, int threads) {
    this(modelDir, threads, DEFAULT_ORG_SUFFIXES);
  }

  /**
   * 把配置里的模型目录解析成"真的能找到 model.onnx 的那个位置"。
   *
   * <p>★ 为什么需要这一步：配置默认值 {@code models/bert-chinese-ner-onnx} 是相对**仓库根**写的， 而后端常常从 {@code backend/}
   * 启动 —— 于是 {@code modelDir.resolve("model.onnx")} 指向 {@code backend/models/…}，那里没有文件。结果是：加载失败 →
   * {@code recognize()} 返回空 → 实体与关系表恒 0 行，而文档状态照常 {@code SUCCEEDED}（`#376`）。
   *
   * <p>★ 状态页（{@code EntityRecognizerStatusAdapter}）必须调用**同一个方法**：
   * 曾经两处各写一份解析，于是出现过"状态页说三个文件都在、实际加载说文件不存在" 这种自相矛盾的读数 —— 健康判定与真实使用不是同一个函数算出来的，它就不是健康状态。
   *
   * <p>判据用 {@code model.onnx} 是否存在（不是"目录是否存在"）：只有目录没有模型的半截下载 不该被当成可用。
   *
   * @param configured 配置值（可能是相对路径）
   * @return 能找到 {@code model.onnx} 的路径；都找不到时返回 {@code configured} 原值， 让报错信息指向人真正配置的那个位置
   */
  public static Path resolveModelDir(Path configured) {
    if (configured == null || configured.isAbsolute()) {
      return configured;
    }
    if (Files.isRegularFile(configured.resolve("model.onnx"))) {
      return configured;
    }
    Path fromParent = Path.of("..").resolve(configured).normalize();
    if (Files.isRegularFile(fromParent.resolve("model.onnx"))) {
      return fromParent;
    }
    return configured;
  }

  /**
   * 模型目录（只读）。
   *
   * <p>模型页要显示"它从哪里加载"，见 {@code EntityRecognizerStatusAdapter}。
   */
  public Path modelDir() {
    return modelDir;
  }

  /**
   * 当前进程是否已把模型载入内存。
   *
   * <p>★ onnx 是惰性加载的（第一次抽取才读文件），所以"配置正确"与"已经能用"是两件事 —— 模型页要能分别显示它们。这个方法<b>不触发加载</b>：打开模型页不该顺手读
   * 400MB。
   */
  public boolean loaded() {
    return session != null;
  }

  /**
   * 上次加载失败的原因，没失败过为 {@code null}。
   *
   * <p>与 {@link #loaded()} 配合：它的值就是"为什么没加载上"，直接给人看。
   */
  public String loadFailure() {
    return loadFailure;
  }

  @Override
  public String name() {
    return "onnx-bert-ner";
  }

  @Override
  public List<String> supportedTypes() {
    return List.of("ORG", "PERSON");
  }

  /**
   * 关系交给规则层。
   *
   * <p>两个 {@code Relation} 是不同包里的两个 record（{@code EntityExtractor.Relation} 在 application、{@link
   * EntityRecognizer.Relation} 在 domain），必须显式转换，不能直接返回。
   */
  @Override
  public List<Relation> relations(String text) {
    return relationExtractor.relations(text).stream()
        .map(r -> new Relation(r.type(), r.from(), r.to()))
        .toList();
  }

  /**
   * 带实体集合的关系抽取：把本次识别到的实体名与类型交给规则层。
   *
   * <p>★ 少了这一步，「张三任信息中心主任」就成不了边 —— 规则层自己抽不出真实人名 （只认「张伟经理」），`WORKS_AT` 的左端在人名上永远找不到实体。
   */
  @Override
  public List<Relation> relations(String text, List<Recognized> entities) {
    Map<String, String> knownTypes = new LinkedHashMap<>();
    if (entities != null) {
      for (Recognized r : entities) {
        if (r != null && r.name() != null && r.type() != null) {
          knownTypes.putIfAbsent(r.name(), r.type());
        }
      }
    }
    return relationExtractor.relations(text, knownTypes).stream()
        .map(r -> new Relation(r.type(), r.from(), r.to()))
        .toList();
  }

  @Override
  public List<Recognized> recognize(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    if (!ensureLoaded()) {
      return List.of();
    }
    try {
      return infer(text);
    } catch (RuntimeException | OrtException e) {
      LOG.warn("#实体抽取 onnx 推理失败，本次不抽（文本长度 {}）：{}", text.length(), e.toString());
      return List.of();
    }
  }

  private boolean ensureLoaded() {
    if (session != null) {
      return true;
    }
    if (loadFailure != null) {
      return false;
    }
    synchronized (this) {
      if (session != null) {
        return true;
      }
      if (loadFailure != null) {
        return false;
      }
      Path onnxFile = modelDir.resolve("model.onnx");
      if (!Files.exists(onnxFile)) {
        loadFailure = "模型文件不存在：" + onnxFile.toAbsolutePath();
        LOG.warn(
            "#实体抽取 用 onnx 识别器但{}；本次不抽。下载：python tools/fetch_onnx_models.py --only ner",
            loadFailure);
        return false;
      }
      try {
        // ★ 一个 JVM 只有一个 OrtEnvironment（getEnvironment() 全局一份）；能卸载的只有 OrtSession
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(threads);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.labels = readLabels(modelDir.resolve("config.json"));
        this.tokenizer =
            HuggingFaceTokenizer.builder()
                .optTokenizerPath(modelDir.resolve("tokenizer.json"))
                .optMaxLength(512)
                .optTruncation(true)
                .optAddSpecialTokens(true)
                .optPadding(false)
                .build();
        this.session = environment.createSession(onnxFile.toString(), options);
        LOG.info(
            "#实体抽取 onnx 识别器已加载：{}（标签 {} 类，机构类 {} 个，线程 {}）",
            onnxFile.toAbsolutePath(),
            labels.size(),
            ORG_LABELS.stream()
                .filter(l -> labels.containsAll(List.of("B-" + l, "I-" + l)))
                .count(),
            threads);
        return true;
      } catch (IOException | OrtException e) {
        loadFailure = e.toString();
        LOG.warn("#实体抽取 onnx 识别器加载失败，本次不抽：{}", loadFailure);
        return false;
      }
    }
  }

  /** 从 `config.json` 的 `id2label` 读标签。顺序是模型自带的，写死必错。 */
  static List<String> readLabels(Path configPath) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readString(configPath));
    JsonNode id2label = root.path("id2label");
    if (!id2label.isObject() || id2label.isEmpty()) {
      throw new IOException("config.json 里没有 id2label：" + configPath);
    }
    // id2label 的 key 是字符串序号，按数值排序而不是按字典序（否则 "10" 排在 "2" 前面）
    List<Map.Entry<Integer, String>> entries = new ArrayList<>();
    for (Map.Entry<String, JsonNode> e : id2label.properties()) {
      JsonNode value = e.getValue();
      entries.add(
          Map.entry(
              Integer.parseInt(e.getKey()),
              value.isTextual() ? value.asText() : value.path("label").asText()));
    }
    entries.sort(Map.Entry.comparingByKey());
    List<String> out = entries.stream().map(Map.Entry::getValue).toList();
    boolean hasOrg =
        out.stream().anyMatch(l -> l.startsWith("B-") && ORG_LABELS.contains(l.substring(2)));
    if (!hasOrg) {
      throw new IOException("标签里没有任何机构类（期望 " + ORG_LABELS + "）：" + out);
    }
    return out;
  }

  private List<Recognized> infer(String text) throws OrtException {
    Encoding encoded = tokenizer.encode(text);
    long[] ids = encoded.getIds();
    long[] mask = encoded.getAttentionMask();
    long[][] idBatch = {ids};
    long[][] maskBatch = {mask};

    Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
    OrtSession active = session;
    List<String> activeLabels = labels;
    try (OnnxTensor idTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), idBatch);
        OnnxTensor maskTensor =
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), maskBatch)) {
      feeds.put("input_ids", idTensor);
      feeds.put("attention_mask", maskTensor);
      if (active.getInputNames().contains("token_type_ids")) {
        try (OnnxTensor typeTensor =
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[1][ids.length])) {
          feeds.put("token_type_ids", typeTensor);
          return decode(active.run(feeds).get(0), encoded, activeLabels);
        }
      }
      return decode(active.run(feeds).get(0), encoded, activeLabels);
    }
  }

  /**
   * 把 per-token 标签拼成 span。
   *
   * <p>{@code ##} 前缀表示这个 token 接在上一个后面（WordPiece）；中文基本一字一格， 但标点与数字仍会出现子词，所以要处理。
   */
  private List<Recognized> decode(Object output, Encoding encoded, List<String> activeLabels)
      throws OrtException {
    float[][][] logits = (float[][][]) ((OnnxTensor) output).getValue();
    String[] tokens = encoded.getTokens();
    long[] ids = encoded.getIds();
    Set<Long> special = specialIds();

    List<String> spans = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < ids.length; i++) {
      if (special.contains(ids[i])) {
        flush(spans, current);
        continue;
      }
      int best = 0;
      for (int c = 1; c < logits[0][i].length; c++) {
        if (logits[0][i][c] > logits[0][i][best]) {
          best = c;
        }
      }
      String label = activeLabels.get(best);
      String piece = tokens[i].startsWith("##") ? tokens[i].substring(2) : tokens[i];
      if (label.startsWith("B-")) {
        flush(spans, current);
        if (ORG_LABELS.contains(label.substring(2))) {
          current.append(piece);
        }
      } else if (label.startsWith("I-") && current.length() > 0) {
        current.append(piece);
      } else if (current.length() > 0 && isOrgSuffixStart(piece)) {
        // ★ 边界扩展（0.6.6）：模型把「部」标成 O，但它是机构后缀、且紧跟在一个机构名
        //   之后 —— 这是 WordPiece 的边界问题，不是"这个字不是机构的一部分"。
        //   实测（2026-11-11）：「财务资产部负责预算」被标成 B-organization(财务资产) + O(部)
        //   ⇒ span 是「财务资产」，随后被 compact() 的后缀过滤整条丢掉，三个机构只剩一个。
        //   ★ 不做这一步的后果不是"少一个字"，是"整条实体消失且没有日志"。
        current.append(piece);
      } else {
        flush(spans, current);
      }
    }
    flush(spans, current);

    // ★ 第二遍：人名（§11.36.1 的 B 项）。
    //   不复用上面那遍的缓冲，因为人名的收敛规则与机构完全不同：
    //   机构靠后缀表、人名靠长度；把两种类型塞进同一个 StringBuilder 会让
    //   「张伟」被 isOrgSuffixStart 的边界扩展误接上去。
    //   代价是再扫一遍 logits —— 29 类 × 512 格，与一次前向相比可以忽略。
    List<String> personSpans = new ArrayList<>();
    StringBuilder person = new StringBuilder();
    for (int i = 0; i < ids.length; i++) {
      if (special.contains(ids[i])) {
        flush(personSpans, person);
        continue;
      }
      int best = 0;
      for (int c = 1; c < logits[0][i].length; c++) {
        if (logits[0][i][c] > logits[0][i][best]) {
          best = c;
        }
      }
      String label = activeLabels.get(best);
      String piece = tokens[i].startsWith("##") ? tokens[i].substring(2) : tokens[i];
      if (label.startsWith("B-")) {
        flush(personSpans, person);
        if (PERSON_LABELS.contains(label.substring(2))) {
          person.append(piece);
        }
      } else if (label.startsWith("I-") && person.length() > 0) {
        person.append(piece);
      } else {
        flush(personSpans, person);
      }
    }
    flush(personSpans, person);

    List<Recognized> out = new ArrayList<>();
    for (String span : compact(spans)) {
      out.add(new Recognized("ORG", span, 0.9));
    }
    for (String span : personSpans) {
      // ★ 长度 2-4：单字几乎不是完整人名（是姓氏碎片），五字以上多半是两个名字连在一起。
      if (span.length() >= 2 && span.length() <= 4) {
        out.add(new Recognized("PERSON", span, 0.65));
      }
    }
    return out;
  }

  private static void flush(List<String> spans, StringBuilder current) {
    if (current.length() > 0) {
      spans.add(current.toString());
      current.setLength(0);
    }
  }

  /**
   * 这一个 token 是不是某个机构后缀的**开头**（用于边界扩展）。
   *
   * <p>★ 只接受单字 token：多字 token 说明 WordPiece 把几个字并成了一格，那种情况下 这格的归属已经由模型自己判断过了，我们再猜就是在覆盖它。
   *
   * <p>★ 用 {@code startsWith} 而不是 {@code equals}：后缀表里有「领导小组」「委员会」这类
   * 多字后缀，它们的开头一个字（「领」「委」）同样是"机构名还没写完"的信号。
   */
  private boolean isOrgSuffixStart(String piece) {
    return piece.length() == 1 && orgSuffixes.stream().anyMatch(s -> s.startsWith(piece));
  }

  private static Set<Long> specialIds() {
    return Set.of(0L, 100L, 101L, 102L, 103L);
  }

  /**
   * 碎片收敛：去虚词前缀 → 后缀过滤 → 保留被同名长串包含时最长的那个。
   *
   * <p>★ 与离线探针 {@code sla/probes/ner.py} 的 {@code postprocess()} 同口径， 两处的读数才能互相印证（探针 30 段
   * 100.0%，这里必须也能达到）。
   */
  List<String> compact(List<String> spans) {
    LinkedHashSet<String> cleaned = new LinkedHashSet<>();
    for (String raw : spans) {
      String span = stripVirtualPrefix(raw);
      if (span.length() >= 3 && orgSuffixes.stream().anyMatch(span::endsWith)) {
        cleaned.add(span);
      }
    }
    if (cleaned.isEmpty()) {
      return List.of();
    }
    List<String> longest = new ArrayList<>();
    for (String candidate : cleaned) {
      boolean covered = false;
      for (String other : cleaned) {
        if (!candidate.equals(other) && other.contains(candidate)) {
          covered = true;
          break;
        }
      }
      if (!covered) {
        longest.add(candidate);
      }
    }
    return longest.isEmpty() ? List.copyOf(cleaned) : longest;
  }

  private static String stripVirtualPrefix(String span) {
    int at = 0;
    while (at < span.length() && VIRTUAL_PREFIX.indexOf(span.charAt(at)) >= 0) {
      at++;
    }
    return span.substring(at);
  }

  /** 只关 OrtSession 与 tokenizer —— OrtEnvironment 是进程级单例，不关（见类注释）。 */
  @PreDestroy
  public void close() {
    OrtSession active = session;
    this.session = null;
    if (active != null) {
      try {
        active.close();
      } catch (OrtException e) {
        LOG.debug("关闭 onnx 会话失败：{}", e.toString());
      }
    }
    HuggingFaceTokenizer t = tokenizer;
    this.tokenizer = null;
    if (t != null) {
      t.close();
    }
  }
}
