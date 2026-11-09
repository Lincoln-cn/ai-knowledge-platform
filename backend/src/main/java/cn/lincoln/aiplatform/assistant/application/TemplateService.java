package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.Template;
import cn.lincoln.aiplatform.assistant.domain.TemplateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 模板中心的应用层（{@code #295}）。
 *
 * <h3>★★ 这一层薄，是因为"克隆一个应用"早就有了</h3>
 *
 * <p>{@link AssistantService} 里的 {@code export} / {@code checkImport} / {@code importBundle}
 * 本来就是"配置快照 ↔ 应用"的完整往返（导出包的 {@code format} 有版本号，导入时校验）。 模板要做的只是把这个往返**存起来**：
 *
 * <ul>
 *   <li>「从应用存成模板」= {@code export(id, false)} 得到 bundle，存进 {@code as_template.payload}
 *   <li>「从模板建应用」= 取出 {@code payload} 当 bundle，调 {@code importBundle(...)}
 * </ul>
 *
 * <p>★ 由此得到一条纪律：<strong>模板里不放"应用 id"以外的活引用</strong>。bundle 里带的是 知识库 / 提示词的引用，导入时由 {@code
 * skipMissingReferences} 决定"缺了就跳过"还是"报错"—— 那是导入这条链路已经定好的语义，模板中心不该再发明一套。
 *
 * <h3>★ payload 为什么直接用 bundle 的形状</h3>
 *
 * <p>我一开始想手写一份"只放建应用需要的那几格"的映射，写的时候发现那个论证是多余的： {@link AssistantBundleView}
 * <strong>本来就是</strong>"重新创建这个应用所需的一切"， 它里面没有 {@code callCount} / {@code tokenCount} / {@code
 * satisfaction} / {@code published} —— 那些运行时计数在 {@link AssistantView} 上，不在 bundle 上。
 *
 * <p>所以两个方向都走同一个 {@link ObjectMapper}： 手写 13 格映射的唯一收益是"bundle 加字段时模板 payload 不会跟着多一格"，
 * 而那个收益的前提（bundle 会带运行时计数）不成立。手写反而多一处会随 bundle 演进悄悄失效的代码。
 *
 * <h3>为什么这一版不做「改模板」</h3>
 *
 * <p>模板是不可变快照。改模板的正确做法是"存一个新模板"，否则已经克隆出去的应用不知道自己 当初是从哪个版本来的。所以这里只有 {@code create} 与 {@code
 * delete}，没有 {@code update}。
 */
@Service
public class TemplateService {

  /** 默认分类（列表页按它分组） */
  private static final String DEFAULT_CATEGORY = "GENERAL";

  private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

  private final TemplateRepository templates;
  private final AssistantService assistants;
  private final ObjectMapper json;

  public TemplateService(
      TemplateRepository templates, AssistantService assistants, ObjectMapper json) {
    this.templates = templates;
    this.assistants = assistants;
    this.json = json;
  }

  /** 列表（未删，按分类 + 名称稳定排序） */
  @Transactional(readOnly = true)
  public List<Template> list() {
    return templates.listLive();
  }

  /** 取一个 */
  @Transactional(readOnly = true)
  public Template get(UUID id) {
    return templates
        .findLive(id)
        .orElseThrow(() -> new IllegalArgumentException("模板不存在或已删除：" + id));
  }

  /**
   * 把某个应用当前配置存成模板。
   *
   * @param sourceAppId 源应用
   * @param name 模板名（未删范围内唯一）
   * @param description 一句话说明
   * @param category 分类（空 ⇒ {@value #DEFAULT_CATEGORY}）
   * @param actorId 操作人
   */
  @Transactional
  public Template createFromApp(
      UUID sourceAppId, String name, String description, String category, UUID actorId) {
    if (templates.nameTaken(name, null)) {
      throw new IllegalArgumentException("已有同名模板：" + name);
    }
    // ★ includeDocuments=false：模板只带"怎么配"，不带"库里有什么文档"。
    //   带文档的话模板会随源库体积膨胀，而且克隆出来的应用会莫名其妙多出一批别人库里的文档。
    AssistantBundleView bundle = assistants.export(sourceAppId, false, actorId);

    Instant now = Instant.now();
    Template template =
        new Template(
            UUID.randomUUID(),
            name,
            description,
            category == null || category.isBlank() ? DEFAULT_CATEGORY : category,
            sourceAppId,
            json.convertValue(bundle, PAYLOAD_TYPE),
            false,
            now,
            actorId,
            now,
            actorId);
    return templates.save(template);
  }

  /**
   * 从模板建一个新应用。
   *
   * @param templateId 模板
   * @param skipMissingReferences 缺引用的知识库 / 提示词是"跳过"还是"报错"
   * @param ownerId 归属人
   * @return 新应用的视图
   */
  @Transactional
  public AssistantView instantiate(UUID templateId, boolean skipMissingReferences, UUID ownerId) {
    Template template = get(templateId);
    AssistantBundleView bundle = json.convertValue(template.payload(), AssistantBundleView.class);
    return assistants.importBundle(bundle, skipMissingReferences, ownerId);
  }

  /** 删除（内置模板拒绝） */
  @Transactional
  public void delete(UUID id, UUID actorId) {
    Template template = get(id);
    template.requireEditable();
    templates.markDeleted(id, actorId);
  }
}
