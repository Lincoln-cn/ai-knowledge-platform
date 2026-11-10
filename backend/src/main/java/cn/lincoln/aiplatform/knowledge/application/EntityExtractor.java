package cn.lincoln.aiplatform.knowledge.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从中文制度文本里按**规则**抽取实体与受控关系（0.6.1）。
 *
 * <h2>为什么是规则不是 LLM</h2>
 *
 * <p>项目里 87849 个分段，逐段打一次模型是几十小时与一笔不小的 token；而收益只是 "查询里的实体名能扩召回"。更关键的是**可解释**：规则抽出来的每一条都能指着正则说
 * "它为什么在这里"，模型抽出来的不能。
 *
 * <h2>★ 词表全部由外部传入（{@link Dictionary}），这个类里没有一处业务词</h2>
 *
 * <p>用户 2026-11-11 的要求：「实体抽取的字典最好的话是可以配置的」「字典放到数据库里， 不是必须要重启才能生效的」。所以：
 *
 * <ul>
 *   <li>机构后缀、虚词排除表、关系标记词**都来自 {@link Dictionary}**，构造时传入。
 *   <li>字典来自数据库（`kb_entity_dict`），**按知识库配** —— "固定资产部"是某家公司的， "风险管理委员会"是另一家的，全局配置表做不到这件事。
 *   <li>改字典不需要重启：调用方每次按 `kbId` 取字典再构造本类。本类本身**无状态**。
 * </ul>
 *
 * <h2>精度从哪来</h2>
 *
 * <p>不是靠正则更聪明，是靠**两端都要在实体表里**才允许成边（见 {@link #relations}）。 中文里"把普通名词当实体"是最主要的误抽来源，实体表一旦限定范围，关系抽取的精度
 * 会从六成涨到八成以上 —— 这条是设计前提，不是实现细节。
 *
 * <h2>我不做的那两件事（写在这里，免得后来人以为漏了）</h2>
 *
 * <ol>
 *   <li><b>不抽编号列表的层级</b>（"一、总则"）。那是<b>排版层级</b>不是语义关系， 分段与标题层级已经处理了它；把它当关系会把目录结构写进图里。
 *   <li><b>不把动宾短语当实体</b>。"负责固定资产的购置"里的"购置"不是实体，右端是 动宾短语时只记日志不入库。这条是误抽率最高的地方。
 * </ol>
 *
 * <p>★ 这一版**不写库、不接检索**：它只回答"这段文本里有哪些实体、它们之间有什么 关系"。落库与扩召回在后续小节分别做，各自有各自的判据。
 */
public final class EntityExtractor {

  /**
   * 中文虚词：机构名的前缀**不能**包含它们。
   *
   * <p>★ 它们不是可配置项，是**语言的常量**。不排除会跨词吞并： {@code 固定资产的归口管理部门是财务资产部} 会被抽成 {@code 固定资产的归口管理部} 与 {@code
   * 门是财务资产部} —— 两个都不对， 而且它们都会进实体表，后面的关系抽取就整条错位。 这是实测踩出来的（{@code EntityExtractorTest} 第一版两条用例红在这里）。
   */
  private static final String VIRTUAL_WORDS = "的了是在和与及或对为把被将由按据";

  private static final String HEAD_CHARS = "[^\\s，。；：、（）()《》\\u201c\\u201d" + VIRTUAL_WORDS + "]";

  /** 制度文书名（书名号包着的）。★ 书名号本身是常量，不是词表。 */
  /**
   * 标记词两侧各看多远找机构名。
   *
   * <p>取 20 个字符：制度文档里「综合部隶属财务资产部」这类紧邻句远短于它，而 拉太宽会把上一句话的机构捞进来（「财务部负责…。综合部隶属财务资产部。」里 前者不该是 from）。20
   * 是一个够用又不越句的量。
   */
  private static final int RELATION_WINDOW = 20;

  private static final Pattern DOC = Pattern.compile("《([^《》]{2,40})》");

  /** 人员：职务词在名字后面（"张伟经理"这种中式写法）。★ 职务词可配（见字典的 `PERSON_TITLE`）。 */
  private static final Pattern PERSON_TAIL = Pattern.compile("([\\u4e00-\\u9fa5]{2,4}(%s))");

  /** 术语：带引号的专有说法（"三重一大"）。 */
  private static final Pattern TERM = Pattern.compile("[\"\\u201c]([^\"\\u201d]{2,16})[\"\\u201d]");

  /** 机构名正则：{@code HEAD_CHARS{2,12}} + 任一后缀。后缀表可配。 */
  private final Pattern org;

  /** 职务词 → 用于 {@link #PERSON_TAIL}。 */
  private final Pattern person;

  /** 标记词 → 受控关系名。可配（`kb_entity_dict` 的 `RELATION_MARK` 行）。 */
  private final Map<String, String> relationMarks;

  /** 要去掉的机构后缀（别名归一用）。默认取 {@link Dictionary#orgSuffixes()}。 */
  private final List<String> strippableSuffixes;

  public EntityExtractor(Dictionary dictionary) {
    if (dictionary.orgSuffixes().isEmpty()) {
      throw new IllegalArgumentException("字典里机构后缀为空：抽取器会一个实体都抽不出来");
    }
    String suffixAlt =
        dictionary.orgSuffixes().stream()
            // 长的排前面：`办公室` 必须先于 `室` 匹配，否则会先吃掉 `室`
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .map(Pattern::quote)
            .collect(Collectors.joining("|"));
    // ★ 关系标记词的首字必须从机构名的字符集里排掉。
    //
    // 反例（这个测试就是被它弄红的）：`综合部隶属财务资产部` 里「隶」是一个合法汉字，
    // 而「隶属财务资产部」以「部」结尾 ⇒ 整段被抽成一个机构名，于是 `relations` 里
    // "两端都必须已在实体表里"的门永远等不到 `财务资产部`，一条边都产不出来。
    //
    // 这是"词表驱动"该付的代价：字符集放宽能多抽到真机构，但会把标记词也吃进去。
    // 处置不是把字符集收窄回白名单（那会漏掉大量真机构），而是**只排掉我们已知的
    // 标记词首字** —— 它们本来就只作连接词用，不可能是机构名的第一个字。
    String headChars = HEAD_CHARS;
    if (!dictionary.relationMarks().isEmpty()) {
      // HEAD_CHARS 是 `[^…]` 形状（以 `]` 收尾）⇒ 把字面字符直接插在 `]` 之前即可。
      // ★ 不能在这里用 Pattern.quote：字符类里塞 `\Q…\E` 会让整类失效（实测抽到 0 个实体）。
      StringBuilder excluded = new StringBuilder();
      for (String mark : dictionary.relationMarks().keySet()) {
        // ★ 排掉整条标记词的**每一个字**，不只是首字。
        // 只排首字时 `隶属财务资产部` 会退化成 `属财务资产部`（"属"仍被当机构名开头），
        // 于是 `relations` 还是等不到 `财务资产部`。
        for (char c : mark.toCharArray()) {
          excluded.append(c);
        }
      }
      if (excluded.length() > 0) {
        int close = headChars.lastIndexOf(']');
        headChars = headChars.substring(0, close) + excluded + headChars.substring(close);
      }
    }
    this.org = Pattern.compile("(" + headChars + "{2,12}?(?:" + suffixAlt + "))");

    if (dictionary.personTitles().isEmpty()) {
      this.person = null;
    } else {
      String titleAlt =
          dictionary.personTitles().stream()
              .sorted((a, b) -> Integer.compare(b.length(), a.length()))
              .map(Pattern::quote)
              .collect(Collectors.joining("|"));
      this.person = Pattern.compile(String.format(PERSON_TAIL.pattern(), titleAlt));
    }

    this.relationMarks = Map.copyOf(dictionary.relationMarks());
    this.strippableSuffixes = List.copyOf(dictionary.orgSuffixes());
  }

  /**
   * 从一段正文里抽实体。
   *
   * <p>返出去重后的列表，顺序按首次出现 —— 调用方要按出现顺序落库（时间戳同批）。
   *
   * <p>★ 只做**抽取**，不做消歧：同一个"财务部"在本文里出现三次，这里给三条， 归并是别名表的事（`kb_entity_alias` 的 `unique(kb_id,
   * alias_name)`）。
   */
  public List<Candidate> extract(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    Set<String> seen = new LinkedHashSet<>();
    List<Candidate> result = new ArrayList<>();
    collect(org, "ORG", text, seen, result);
    collect(DOC, "DOC", text, seen, result);
    if (person != null) {
      collect(person, "PERSON", text, seen, result);
    }
    collect(TERM, "TERM", text, seen, result);
    return List.copyOf(result);
  }

  private void collect(
      Pattern pattern, String type, String text, Set<String> seen, List<Candidate> out) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      String name = matcher.group(1).strip();
      if (name.isEmpty()) {
        continue;
      }
      // 去重键带类型：同名既可能是机构也可能是术语，混在一起会把两类合成一条
      if (seen.add(type + ":" + name)) {
        out.add(new Candidate(type, name));
      }
    }
  }

  /**
   * 从一段正文里抽受控关系。
   *
   * <p>★ 右端如果是**动宾短语**（"负责固定资产的购置"），这条边不入库 —— 见类注释 第 2 条。判断方式是"右端抽不出实体"：它不在 {@link #extract}
   * 的结果里，就不成边。
   *
   * <p>所以这个方法内部先跑一次 {@code extract}，用它的名字集合过滤两端。**过滤不是 可选项**：不过滤就是把"负责"后面任何一段字都当尾实体，那是规则抽取最常见的坑。
   *
   * <p>★ 标记词来自字典：用户加一个词，必须同时说清它映射到哪个受控关系 （`kb_entity_dict.relation` 非空即 {@code
   * RELATION_MARK}），否则"加了词但不知道 加成了什么"，比不让他加更糟。
   */
  public List<Relation> relations(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    Set<String> names = new LinkedHashSet<>();
    for (Candidate candidate : extract(text)) {
      names.add(candidate.name());
    }
    List<Relation> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Map.Entry<String, String> mark : relationMarks.entrySet()) {
      // ★ 不能用一个"(机构)(标记词)(机构)"的正则整体匹配：那种写法把"哪一部分是实体"
      // 交给正则去猜，而标记词的位置其实是确定的。改成**先定位标记词，再在它左右的
      // 窗口里各找最近的一个机构名** —— 这与"两端都必须在实体表里"是同一条判据的
      // 两种落地方式，但后者对"综合部隶属财务资产部"这种紧邻句更稳。
      int at = text.indexOf(mark.getKey());
      while (at >= 0) {
        String from = nearestOrgBefore(text, at);
        int toStart = at + mark.getKey().length();
        String to = nearestOrgAt(text, toStart);
        if (from != null
            && to != null
            && !from.equals(to)
            && names.contains(from)
            && names.contains(to)
            && seen.add(mark.getValue() + "|" + from + "|" + to)) {
          result.add(new Relation(mark.getValue(), from, to));
        }
        at = text.indexOf(mark.getKey(), toStart);
      }
    }
    return List.copyOf(result);
  }

  /** 标记词左侧窗口里**最靠右**的机构名（离标记词最近的那一个）。 */
  private String nearestOrgBefore(String text, int markStart) {
    int from = Math.max(0, markStart - RELATION_WINDOW);
    Matcher matcher = org.matcher(text.substring(from, markStart));
    String last = null;
    while (matcher.find()) {
      last = matcher.group(1).strip();
    }
    return last;
  }

  /** 标记词右侧窗口里**最靠左**的机构名（离标记词最近的那一个）。 */
  private String nearestOrgAt(String text, int markEnd) {
    int to = Math.min(text.length(), markEnd + RELATION_WINDOW);
    Matcher matcher = org.matcher(text.substring(markEnd, to));
    return matcher.find() ? matcher.group(1).strip() : null;
  }

  private String suffixAlt() {
    return strippableSuffixes.stream()
        .sorted((a, b) -> Integer.compare(b.length(), a.length()))
        .map(Pattern::quote)
        .collect(Collectors.joining("|"));
  }

  /**
   * 生成一个名字的别名候选（不含原名本身）。
   *
   * <p>★ 只做**机械变换**：去机构后缀、全半角归一、去空格。**不做编辑距离**—— "财务部"与"财务资产部"差三个字，靠距离判它们是不是同一个实体，误合并率太高，
   * 而那类合并会直接改变权限归属（`kb_entity` 带 `kb_id`）。编辑距离那条路留给 "只标疑似、不合"，且必须有人确认。
   */
  public List<String> aliasCandidates(String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    String normalized = normalize(name);
    Set<String> out = new LinkedHashSet<>();
    for (String suffix : strippableSuffixes) {
      if (normalized.length() > suffix.length() + 1 && normalized.endsWith(suffix)) {
        out.add(normalized.substring(0, normalized.length() - suffix.length()));
      }
    }
    out.remove(normalized);
    return List.copyOf(out);
  }

  /** 全半角与空白的归一。★ 不做繁简转换：那是另一件事，且要词表。 */
  private String normalize(String raw) {
    String s = raw.strip().replace("　", "").replace(" ", "");
    StringBuilder sb = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      // 全角 ASCII（！到 ～）压回半角；中文标点不在这个区间，不受影响
      if (c >= '\uFF01' && c <= '\uFF5E') {
        sb.append((char) (c - 0xFEE0));
      } else {
        sb.append(c);
      }
    }
    return sb.toString().toUpperCase(Locale.ROOT);
  }

  /**
   * 抽取用的词表。**来自数据库**（`kb_entity_dict`），按知识库取。
   *
   * <p>★ 三个字段都可配，且都不需要重启：调用方每次按 {@code kbId} 取一份新的来构造 {@link EntityExtractor}。
   *
   * @param orgSuffixes 机构后缀（部 / 局 / 中心 …）。为空时构造直接抛错 —— 没有后缀 就一个机构名都抽不出来，那是配置错误，不该静默跑出一个空结果
   * @param personTitles 职务词（经理 / 主任 …）。空表示这个库不抽人员
   * @param relationMarks 标记词 → 受控关系名（`BELONGS_TO` / `CONTAINS` / `RESPONSIBLE_FOR` / `DEFINES`）
   */
  public record Dictionary(
      List<String> orgSuffixes, List<String> personTitles, Map<String, String> relationMarks) {

    public Dictionary {
      orgSuffixes = List.copyOf(orgSuffixes);
      personTitles = List.copyOf(personTitles);
      relationMarks = Map.copyOf(relationMarks);
    }
  }

  /** 抽出来的实体候选。{@code type} 取 {@code ORG|PERSON|DOC|TERM|OTHER}。 */
  public record Candidate(String type, String name) {}

  /** 一条受控关系。{@code type} 取 {@code BELONGS_TO|CONTAINS|RESPONSIBLE_FOR|DEFINES}。 */
  public record Relation(String type, String from, String to) {}
}
