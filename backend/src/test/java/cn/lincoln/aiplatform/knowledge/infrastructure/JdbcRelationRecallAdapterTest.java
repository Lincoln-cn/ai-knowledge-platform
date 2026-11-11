package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcRelationRecallAdapter} 的两跳召回。
 *
 * <p>★ 这一组测试守的核心是 `§11.36.1` 的 C 项：<b>问题问 A、答案写在「B 与 C 的关系」那一段时，
 * 只查一跳永远找不到</b>。用例的形状必须让「一跳返回空、两跳返回非空」成为可断言的事实， 否则「加了两跳」这件事在测试里看不见（`§11.37.3` 的教训：没有反证的接线等于没接）。
 */
class JdbcRelationRecallAdapterTest {

  private static final UUID KB = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ENTITY_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID ENTITY_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
  private static final UUID SEG_VIA_NEIGHBOR =
      UUID.fromString("cccccccc-0000-0000-0000-000000000003");

  private EntityRecognizer recognizer;
  private EntityRepository entityRepository;
  private EntityRelationRepository relationRepository;
  private JdbcRelationRecallAdapter adapter;

  @BeforeEach
  void setUp() {
    recognizer = mock(EntityRecognizer.class);
    entityRepository = mock(EntityRepository.class);
    relationRepository = mock(EntityRelationRepository.class);
    adapter = new JdbcRelationRecallAdapter(recognizer, entityRepository, relationRepository);
  }

  /**
   * ★ C 项的判据本身：问题只提到 A，A 自己没有关系陈述段，但 A—B 有边、B 的关系段存在。
   *
   * <p>一跳（只用 A 查）返回空；两跳（把 B 加进来）才有段。这个差别就是「两跳真的接上了」。
   */
  @Test
  void secondHopBringsInSegmentsThatOneHopCannotFind() {
    when(recognizer.recognize(KB, "综合部和谁有关系"))
        .thenReturn(List.of(new EntityRecognizer.Recognized("ORG", "综合部", 0.8)));
    when(entityRepository.findIdByName(KB, "ORG", "综合部")).thenReturn(Optional.of(ENTITY_A));

    // 一跳：A 自己没有任何关系陈述段。
    when(relationRepository.segmentIdsOf(eq(KB), eq(java.util.Set.of(ENTITY_A))))
        .thenReturn(List.of());

    // 两跳：A 的邻居是 B；带上 B 之后能查到那一段。
    // ★ stub 的第二个参数必须用 argThat 而不是 List.of(ENTITY_A)：
    //   适配器传进来的 entityIds 是 LinkedHashSet，List.equals(Set) 恒为 false ⇒
    //   stub 静默不匹配、Mockito 返回空 List、「没有邻居」与「有邻居但查不到段」读数一样。
    //   第一次写这条就是这样绿的（错了 3 条里 1 条），坑记在这里。
    when(relationRepository.neighborEntityIds(
            eq(KB),
            org.mockito.ArgumentMatchers.<Collection<UUID>>argThat(
                col -> col != null && col.contains(ENTITY_A))))
        .thenReturn(List.of(ENTITY_B));
    when(relationRepository.segmentIdsOf(
            eq(KB),
            org.mockito.ArgumentMatchers.<Collection<UUID>>argThat(
                col -> col != null && col.contains(ENTITY_A) && col.contains(ENTITY_B))))
        .thenReturn(List.of(SEG_VIA_NEIGHBOR));

    assertThat(adapter.segmentsWithRelations(KB, "综合部和谁有关系")).containsExactly(SEG_VIA_NEIGHBOR);

    // 顺序也是判据的一部分：邻居必须在查段之前被取到。
    // 同上，verify 用 argThat：适配器传的是 LinkedHashSet，不是 List。
    verify(relationRepository)
        .neighborEntityIds(
            eq(KB),
            org.mockito.ArgumentMatchers.<Collection<UUID>>argThat(
                col -> col != null && col.contains(ENTITY_A)));
  }

  /** 没有邻居时不许崩，也不许多查一次 —— 空邻居是正常结果（库里只有孤立实体）。 */
  @Test
  void isolatedEntityFallsBackToOneHopResult() {
    when(recognizer.recognize(KB, "综合部负责什么"))
        .thenReturn(List.of(new EntityRecognizer.Recognized("ORG", "综合部", 0.8)));
    when(entityRepository.findIdByName(KB, "ORG", "综合部")).thenReturn(Optional.of(ENTITY_A));
    when(relationRepository.neighborEntityIds(any(), any())).thenReturn(List.of());
    when(relationRepository.segmentIdsOf(any(), any())).thenReturn(List.of());

    assertThat(adapter.segmentsWithRelations(KB, "综合部负责什么")).isEmpty();
  }

  /** 问题里一个实体都认不出来 ⇒ 不查库（抽取逐次调用，白白往返没有意义）。 */
  @Test
  void noEntityMeansNoDatabaseCall() {
    when(recognizer.recognize(KB, "今天天气怎么样")).thenReturn(List.of());

    assertThat(adapter.segmentsWithRelations(KB, "今天天气怎么样")).isEmpty();
    verify(relationRepository, org.mockito.Mockito.never()).segmentIdsOf(any(), any());
  }
}
