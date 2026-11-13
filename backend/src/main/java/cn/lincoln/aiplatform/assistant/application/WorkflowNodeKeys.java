package cn.lincoln.aiplatform.assistant.application;

import java.util.List;
import java.util.Set;

/**
 * 节点配置键与流程状态键的<strong>唯一词表</strong>（`#337` 第一步）。
 *
 * <h3>★ 为什么要这个类</h3>
 *
 * <p>同一个键曾经被写好几次：{@code kbIds} 在 {@link NodeConfigReader} 里是裸字面量、在 {@code
 * SearchDatasetNodeExecutor} 里是常量；{@code branches} / {@code fallbackBranchId} 在条件节点与数值条件节点里各一份。
 * 两处写同一个名字时，没有任何东西会因为它们分叉而红 —— 那正是 `#335` 的形态（前端发 {@code kbIds}、执行器读 {@code
 * dataset_id_list}，两边各自都绿）。
 *
 * <h3>★ 三组的关系</h3>
 *
 * <ul>
 *   <li>{@link Config} —— <strong>契约键</strong>：与前端 {@code NodeConfigOf} 的同名字段、与 {@code
 *       node-types.json} 的 {@code configFields[].key} 必须逐字相同；
 *   <li>{@link Legacy} —— <strong>画布旧名</strong>：迁移期回退用，`#337` 第 4 步连同前端 22 个 {@code *Legacy} 函数一起删；
 *   <li>{@link Item} —— 数组项内部键（{@code branches[]} / {@code fields[]} 的每一项）。
 * </ul>
 *
 * <p>{@link #configKeys()} 是上面三组的并集，给 {@code WorkflowNodeKeysTest} 当白名单：执行器里出现白名单之外的裸字面量就红。
 *
 * <h3>★ 名字为什么分大小写两套</h3>
 *
 * <p>契约一侧全是 camelCase（{@code modelId} / {@code defaultAnswer}），旧画布一侧全是 snake_case（{@code model_id}
 * / {@code default_answer}）。这不是风格问题：前端把契约形状发给后端，画布旧的 LogicFlow 实例里存的是另一套词， 转换层（{@code
 * graph-format.ts}）在中间翻译。两套名字同时存在这件事本身就是要消灭的对象，而消灭之前必须能一眼看出谁是谁。
 */
public final class WorkflowNodeKeys {

  private WorkflowNodeKeys() {}

  /** 节点配置的根容器键：{@code WorkflowCompiler.Node} 合并后仍叫这个名字。 */
  public static final String CONFIG = "config";

  /** 契约键：与前端 {@code NodeConfigOf} 字段、{@code node-types.json} 的 {@code configFields[].key} 逐字一致。 */
  public static final class Config {

    private Config() {}

    public static final String MODEL_ID = "modelId";
    public static final String KB_IDS = "kbIds";
    public static final String KB_ID = "kbId";
    public static final String DOC_ID = "docId";
    public static final String APPLICATION_ID = "applicationId";
    public static final String BRANCHES = "branches";
    public static final String FALLBACK_BRANCH_ID = "fallbackBranchId";
    public static final String DEFAULT_ANSWER = "defaultAnswer";
    public static final String REWRITE_PROMPT = "rewritePrompt";
    public static final String REPLY_TYPE = "replyType";
    public static final String CONTENT = "content";
    public static final String FIELDS = "fields";
    public static final String RETRIEVAL = "retrieval";
    public static final String TOP_N = "topN";
    public static final String PROLOGUE = "prologue";
    public static final String QUICK_QUESTIONS = "quickQuestions";
    public static final String ROLE_PROMPT = "rolePrompt";
    public static final String PROMPT_IDS = "promptIds";
    public static final String THINKING_ENABLED = "thinkingEnabled";
    public static final String MULTI_TURN = "multiTurn";
  }

  /** 画布旧名：迁移期回退，`#337` 第 4 步删。每一条都对应前端一个 {@code *ToLegacy} 写出的键。 */
  public static final class Legacy {

    private Legacy() {}

    public static final String BRANCH = "branch";
    public static final String FALLBACK_BRANCH_ID = "_fallbackBranchId";
    public static final String DEFAULT_ANSWER = "default_answer";
    public static final String REPLY_TYPE = "reply_type";
    public static final String DATASET_ID_LIST = "dataset_id_list";
  }

  /** 数组项内部键。只收执行器<strong>实际读过</strong>的，不预先给未实现的分支形态留位（留位会变成新的假清单）。 */
  public static final class Item {

    private Item() {}

    public static final String ID = "id";
    public static final String TYPE = "type";
    public static final String EXPRESSION = "expression";
    public static final String CONDITIONS = "conditions";
    public static final String VARIABLE = "variable";
    public static final String OPERATOR = "operator";
    public static final String LOGIC = "logic";
    public static final String VALUE = "value";
    public static final String NAME = "name";
  }

  /** 执行器写进流程状态的键：与 {@code node-types.json} 的 {@code outputs} 逐字一致。 */
  public static final class Output {

    private Output() {}

    public static final String QUERY = "query";
    public static final String ANSWER = "answer";
    public static final String RETRIEVAL_RESULT = "retrieval_result";
    public static final String ACTIVE_BRANCH = "active_branch";
    public static final String REPLY = "reply";
    public static final String DOCUMENT_MARKDOWN = "document_markdown";
    public static final String FORM_DATA = "form_data";
    public static final String BASIC_INFO_FIELDS = "basic_info_fields";
    public static final String BASIC_INFO_INPUT = "basic_info_input";
  }

  /** 契约键集合（不含旧名与数组项键）：{@code node-types.json} 的 {@code configFields[].key} 必须落在这个集合里。 */
  public static Set<String> contractKeys() {
    return Set.of(
        Config.MODEL_ID,
        Config.KB_IDS,
        Config.KB_ID,
        Config.DOC_ID,
        Config.APPLICATION_ID,
        Config.BRANCHES,
        Config.FALLBACK_BRANCH_ID,
        Config.DEFAULT_ANSWER,
        Config.REWRITE_PROMPT,
        Config.REPLY_TYPE,
        Config.CONTENT,
        Config.FIELDS,
        Config.RETRIEVAL,
        Config.TOP_N,
        Config.PROLOGUE,
        Config.QUICK_QUESTIONS,
        Config.ROLE_PROMPT,
        Config.PROMPT_IDS,
        Config.THINKING_ENABLED,
        Config.MULTI_TURN);
  }

  /** 所有配置键（契约 + 旧名 + 数组项）：源码扫描测试的白名单。 */
  public static Set<String> configKeys() {
    return java.util.stream.Stream.of(
            List.copyOf(contractKeys()),
            List.of(
                Legacy.BRANCH,
                Legacy.FALLBACK_BRANCH_ID,
                Legacy.DEFAULT_ANSWER,
                Legacy.REPLY_TYPE,
                Legacy.DATASET_ID_LIST,
                Item.ID,
                Item.TYPE,
                Item.EXPRESSION,
                Item.CONDITIONS,
                Item.VARIABLE,
                Item.OPERATOR,
                Item.LOGIC,
                Item.VALUE,
                Item.NAME,
                CONFIG),
            List.of(
                Output.QUERY,
                Output.ANSWER,
                Output.RETRIEVAL_RESULT,
                Output.ACTIVE_BRANCH,
                Output.REPLY,
                Output.DOCUMENT_MARKDOWN,
                Output.FORM_DATA,
                Output.BASIC_INFO_FIELDS,
                Output.BASIC_INFO_INPUT))
        .flatMap(List::stream)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** 输出键集合：{@code node-types.json} 的 {@code outputs} 必须落在这个集合里。 */
  public static Set<String> outputKeys() {
    return Set.of(
        Output.QUERY,
        Output.ANSWER,
        Output.RETRIEVAL_RESULT,
        Output.ACTIVE_BRANCH,
        Output.REPLY,
        Output.DOCUMENT_MARKDOWN,
        Output.FORM_DATA,
        Output.BASIC_INFO_FIELDS,
        Output.BASIC_INFO_INPUT);
  }
}
