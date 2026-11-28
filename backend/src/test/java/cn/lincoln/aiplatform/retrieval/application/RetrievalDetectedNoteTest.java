package cn.lincoln.aiplatform.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.retrieval.EntityRecallPort;
import cn.lincoln.aiplatform.platform.retrieval.RelationRecallPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 命中测试诊断块里那句说明的判据（0.9.9 · {@code #371} 之后这一批）。
 *
 * <p>★ 它钉的是"四档必须分得开"这件事：失败、没认出来、认出来没关系、有东西可念 —— 这四种在界面上的处置完全不同（前一种是缺陷，后两种是正常），而此前它们全都被压成"这一路没有结果"，
 * 于是失败永远没人知道。
 */
class RetrievalDetectedNoteTest {

  private static EntityRecallPort.Entity entity(String name) {
    return new EntityRecallPort.Entity(name, "ORG", 3);
  }

  private static RelationRecallPort.Relation relation() {
    return new RelationRecallPort.Relation("BELONGS_TO", "信息中心", "运维科", UUID.randomUUID());
  }

  @Test
  @DisplayName("两路都失败时说明里必须有「失败」与「少一路候选」——不许读成没有命中")
  void failureWinsOverEmptiness() {
    String note =
        RetrievalService.detectNote(
            List.of(), List.of(), List.of(), "java.lang.IllegalStateException: 连不上库", null, null);

    assertThat(note).contains("实体扩召回失败").contains("少一路候选");
  }

  @Test
  @DisplayName("两路都空但都没失败 ⇒ 说的是「问句里没有识别到实体」，不是「失败」")
  void emptyIsNotAFailure() {
    String note = RetrievalService.detectNote(List.of(), List.of(), List.of(), null, null, null);

    assertThat(note).contains("没有识别到实体").doesNotContain("失败");
  }

  @Test
  @DisplayName("认出实体但没有关系陈述 ⇒ 说出个数，并指出是没抽边而不是功能坏了")
  void entitiesWithoutRelationsSayWhatToDo() {
    String note =
        RetrievalService.detectNote(
            List.of(entity("信息中心"), entity("运维科")), List.of(), List.of(), null, null, null);

    assertThat(note).contains("认出 2 个实体").contains("没有关系陈述").doesNotContain("失败");
  }

  @Test
  @DisplayName("有东西可念时返回 null —— 界面去念实体与关系本身，不另外解释")
  void whenThereIsSomethingTheNoteIsSilent() {
    String note =
        RetrievalService.detectNote(
            List.of(entity("信息中心")), List.of(relation()), List.of(), null, null, null);

    assertThat(note).isNull();
  }
}
