package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.knowledge.application.EntityExtractor;
import java.util.UUID;

/**
 * 实体抽取字典的读写端口（对应表 {@code kb_entity_dict}）。
 *
 * <p>★ 为什么字典要在库里而不是代码里：用户 2026-11-10 的要求是「字典放到数据库里，不是必须要 重启才能生效的」。抽取器的判定依据（"这个后缀算机构"、"这个标记词映射到
 * BELONGS_TO"）**每个库 都不一样** —— 人事库有"处/科"，法务库有"委员会/办公室"。写死在代码里意味着任何一次调整都要 发版重启，而这是内容运营会天天改的东西。
 *
 * <p>★ 返回的是 {@link EntityExtractor.Dictionary} 而不是实体列表：调用方只关心"抽取器要的那三张 表"，不关心它们怎么存的。仓储负责把 {@code
 * ORG_SUFFIX} / {@code ORG_NAME} 合并成 {@code orgSuffixes}、把 {@code RELATION_MARK} 的 {@code value →
 * relation} 映射成 {@code relationMarks}。
 *
 * <p>★ 没有 default 实现、没有 noop 兜底：字典是抽取的前提，"没装配"与"字典是空的"读数 完全一样（都抽不出实体），必须让前者在启动时就炸出来。
 */
public interface EntityDictionaryPort {

  /**
   * 取某个库的字典。
   *
   * @param kbId 知识库 id
   * @return 该库的字典；库里一行都没有时返回**中文为空的 {@code Dictionary}** 而不是 {@code null} —— {@code null} 会让调用方在第一个
   *     {@code if} 上分叉，而"没有配过"与"配了但是空的" 在抽取器看来是同一件事（都抽不出实体）
   */
  EntityExtractor.Dictionary dictionaryOf(UUID kbId);
}
