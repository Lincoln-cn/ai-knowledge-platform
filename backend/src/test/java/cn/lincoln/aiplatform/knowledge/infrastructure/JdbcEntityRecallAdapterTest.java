package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.EntityAliasRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link JdbcEntityRecallAdapter} 的判据。 */
class JdbcEntityRecallAdapterTest {

  private static final UUID KB = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SEG_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID SEG_B = UUID.fromString("44444444-4444-4444-8444-444444444444");

  private static EntityRecognizer recognizerReturning(List<EntityRecognizer.Recognized> found) {
    EntityRecognizer r = mock(EntityRecognizer.class);
    when(r.recognize(eq(KB), anyString())).thenReturn(found);
    return r;
  }

  /** 默认没有别名 —— 只测「按名字找不到时不该凭空命中」。 */
  private static EntityAliasRepository aliasWith() {
    return mock(EntityAliasRepository.class);
  }

  @Test
  @DisplayName("问题里提到已入库的实体 ⇒ 返回它出现过的段")
  void recallsSegmentsOfKnownEntity() {
    EntityRecognizer rec =
        recognizerReturning(List.of(new EntityRecognizer.Recognized("ORG", "财务资产部", 0.8)));
    EntityRepository repo = mock(EntityRepository.class);
    when(repo.findIdByName(KB, "ORG", "财务资产部")).thenReturn(Optional.of(ENTITY));
    EntityMentionRepository mentions = mock(EntityMentionRepository.class);
    // ★ 故意乱序给，断言输出是升序（名次交给 RRF，这里不预排）
    when(mentions.segmentIdsOf(ENTITY)).thenReturn(List.of(SEG_B, SEG_A));

    List<UUID> got =
        new JdbcEntityRecallAdapter(rec, repo, mentions, aliasWith())
            .segmentsMentionedIn(KB, "财务资产部的职责？");

    assertThat(got).containsExactly(SEG_A, SEG_B);
  }

  @Test
  @DisplayName("问题里抽不出实体 ⇒ 空列表，且不去查库")
  void returnsEmptyWhenNothingRecognized() {
    EntityRecognizer rec = recognizerReturning(List.of());
    EntityRepository repo = mock(EntityRepository.class);
    EntityMentionRepository mentions = mock(EntityMentionRepository.class);

    List<UUID> got =
        new JdbcEntityRecallAdapter(rec, repo, mentions, aliasWith())
            .segmentsMentionedIn(KB, "这条怎么处理？");

    assertThat(got).isEmpty();
    verify(repo, never()).findIdByName(any(), anyString(), anyString());
    verify(mentions, never()).segmentIdsOf(any());
  }

  @Test
  @DisplayName("按名字找不到时走别名：问题里说『财务部』也能召回『财务资产部』的段（0.6.6 的 A 项）")
  void recallsThroughAliasWhenExactNameMisses() {
    EntityRecognizer rec =
        recognizerReturning(List.of(new EntityRecognizer.Recognized("ORG", "财务部", 0.8)));
    EntityRepository repo = mock(EntityRepository.class);
    // 精确名找不到 —— 库里登记的是「财务资产部」
    when(repo.findIdByName(KB, "ORG", "财务部")).thenReturn(Optional.empty());
    EntityAliasRepository alias = mock(EntityAliasRepository.class);
    when(alias.canonicalIds(KB, List.of("财务部"))).thenReturn(java.util.Map.of("财务部", ENTITY));
    EntityMentionRepository mentions = mock(EntityMentionRepository.class);
    when(mentions.segmentIdsOf(ENTITY)).thenReturn(List.of(SEG_A));

    List<UUID> got =
        new JdbcEntityRecallAdapter(rec, repo, mentions, alias)
            .segmentsMentionedIn(KB, "财务部的审批流程？");

    // ★ 判据不是"没崩"，是"别名真的把人带到段上了"。
    //   把 adapter 里那段 aliasRepository.canonicalIds(...) 注掉，这条会红。
    assertThat(got).containsExactly(SEG_A);
  }

  @Test
  @DisplayName("抽到的实体这个库还没入库 ⇒ 空列表（不是错误）")
  void skipsEntityNotInThisKnowledgeBase() {
    EntityRecognizer rec =
        recognizerReturning(List.of(new EntityRecognizer.Recognized("ORG", "综合部", 0.8)));
    EntityRepository repo = mock(EntityRepository.class);
    when(repo.findIdByName(KB, "ORG", "综合部")).thenReturn(Optional.empty());
    EntityMentionRepository mentions = mock(EntityMentionRepository.class);

    assertThat(
            new JdbcEntityRecallAdapter(rec, repo, mentions, aliasWith())
                .segmentsMentionedIn(KB, "综合部负责什么？"))
        .isEmpty();
    verify(mentions, never()).segmentIdsOf(any());
  }

  @Test
  @DisplayName("多个实体提到同一段 ⇒ 去重")
  void deduplicatesSegmentsAcrossEntities() {
    EntityRecognizer rec =
        recognizerReturning(
            List.of(
                new EntityRecognizer.Recognized("ORG", "财务资产部", 0.8),
                new EntityRecognizer.Recognized("ORG", "综合部", 0.8)));
    EntityRepository repo = mock(EntityRepository.class);
    when(repo.findIdByName(eq(KB), eq("ORG"), eq("财务资产部"))).thenReturn(Optional.of(ENTITY));
    when(repo.findIdByName(eq(KB), eq("ORG"), eq("综合部")))
        .thenReturn(Optional.of(UUID.fromString("55555555-5555-4555-8555-555555555555")));
    EntityMentionRepository mentions = mock(EntityMentionRepository.class);
    when(mentions.segmentIdsOf(ENTITY)).thenReturn(List.of(SEG_A));
    when(mentions.segmentIdsOf(UUID.fromString("55555555-5555-4555-8555-555555555555")))
        .thenReturn(List.of(SEG_A, SEG_B));

    List<UUID> got =
        new JdbcEntityRecallAdapter(rec, repo, mentions, aliasWith())
            .segmentsMentionedIn(KB, "财务资产部与综合部谁负责？");

    assertThat(got).containsExactly(SEG_A, SEG_B);
  }

  @Test
  @DisplayName("kbId 为空 ⇒ 空列表，不抽不查")
  void returnsEmptyWithoutKnowledgeBase() {
    EntityRecognizer rec = mock(EntityRecognizer.class);
    EntityRepository repo = mock(EntityRepository.class);
    EntityMentionRepository mentions = mock(EntityMentionRepository.class);

    assertThat(
            new JdbcEntityRecallAdapter(rec, repo, mentions, aliasWith())
                .segmentsMentionedIn(null, "财务资产部"))
        .isEmpty();
    verify(rec, never()).recognize(any(), anyString());
  }

  @Test
  @DisplayName("查库失败必须抛，不许吞成空列表")
  void propagatesRepositoryFailure() {
    EntityRecognizer rec =
        recognizerReturning(List.of(new EntityRecognizer.Recognized("ORG", "财务资产部", 0.8)));
    EntityRepository repo = mock(EntityRepository.class);
    when(repo.findIdByName(any(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("库挂了"));
    EntityMentionRepository mentions = mock(EntityMentionRepository.class);

    // ★ "没有命中"与"没查成"读数一样，后者必须冒出去 —— 否则扩召回静默失效。
    assertThatThrownBy(
            () ->
                new JdbcEntityRecallAdapter(rec, repo, mentions, aliasWith())
                    .segmentsMentionedIn(KB, "财务资产部"))
        .isInstanceOf(IllegalStateException.class);
  }
}
