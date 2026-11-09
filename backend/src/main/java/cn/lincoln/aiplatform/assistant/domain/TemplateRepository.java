package cn.lincoln.aiplatform.assistant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 模板的持久化口（{@code #295}）。
 *
 * <p>刻意只有五个方法：列表 / 取一个 / 存 / 软删 / 判重名。**没有 update-payload** ——
 * 模板是不可变快照，"改模板"的正确做法是"存一个新模板"，否则已经克隆出去的应用会不知道自己 当初是从哪个版本来的。
 */
public interface TemplateRepository {

  /** 列表（只返回未删的），按分类、名称稳定排序 */
  List<Template> listLive();

  /** 按 id 取（未删） */
  Optional<Template> findLive(UUID id);

  /** 未删范围内是否已有这个名称（{@code excludeId} 用于"改名"场景，本版用不到，留参数位） */
  boolean nameTaken(String name, UUID excludeId);

  /** 存（新建或覆盖同名 id 的整行） */
  Template save(Template template);

  /** 软删（内置模板由调用方先调 {@link Template#requireEditable()}） */
  void markDeleted(UUID id, UUID updatedBy);
}
