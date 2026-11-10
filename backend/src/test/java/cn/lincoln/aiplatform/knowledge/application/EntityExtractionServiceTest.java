package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.EntityDictionaryPort;
import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 抽取服务：批内去重、失败不炸整批、空输入不白跑 SQL。
 *
 * <p>不连库：仓储是替身，断的是"服务把什么交给仓储"。
 */
class EntityExtractionServiceTest {

  private static final UUID KB = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  private static EntityRecognizer recognizerOf(
      Map<String, List<EntityRecognizer.Recognized>> byText) {
    return new EntityRecognizer() {
      @Override
      public List<Recognized> recognize(String text) {
        return byText.getOrDefault(text, List.of());
      }

      @Override
      public List<String> supportedTypes() {
        return List.of("ORG");
      }

      @Override
      public String name() {
        return "fake";
      }
    };
  }

  private static EntityExtractionService service(
      EntityRecognizer recognizer, EntityRepository repo, EntityDictionaryPort dict) {
    return service(recognizer, repo, dict, mock(EntityRelationRepository.class));
  }

  private static EntityExtractionService service(
      EntityRecognizer recognizer,
      EntityRepository repo,
      EntityDictionaryPort dict,
      EntityRelationRepository relations) {
    return new EntityExtractionService(
        recognizer, repo, dict, mock(EntityMentionRepository.class), relations);
  }

  @Test
  @DisplayName("两段里出现同一个实体时只交给仓储一份")
  void deduplicatesWithinBatch() {
    EntityRepository repo = mock(EntityRepository.class);
    when(repo.insertAll(eq(KB), anyList())).thenReturn(1);
    EntityRecognizer rec =
        recognizerOf(
            Map.of(
                "综合部隶属财务资产部。", List.of(new EntityRecognizer.Recognized("ORG", "综合部", 0.8)),
                "财务资产部负责预算。", List.of(new EntityRecognizer.Recognized("ORG", "财务资产部", 0.8))));

    int n =
        service(rec, repo, mock(EntityDictionaryPort.class))
            .extractAndStore(KB, List.of("综合部隶属财务资产部。", "财务资产部负责预算。"));

    assertThat(n).isEqualTo(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<EntityRepository.Candidate>> cap =
        ArgumentCaptor.forClass((Class) List.class);
    org.mockito.Mockito.verify(repo).insertAll(eq(KB), cap.capture());
    assertThat(cap.getValue())
        .extracting(EntityRepository.Candidate::name)
        .containsExactly("综合部", "财务资产部");
  }

  @Test
  @DisplayName("同一个实体在不同段里只出现一次")
  void deduplicatesRepeatedEntity() {
    EntityRepository repo = mock(EntityRepository.class);
    when(repo.insertAll(eq(KB), anyList())).thenReturn(1);
    EntityRecognizer rec =
        recognizerOf(
            Map.of(
                "甲段", List.of(new EntityRecognizer.Recognized("ORG", "综合部", 0.8)),
                "乙段", List.of(new EntityRecognizer.Recognized("ORG", "综合部", 0.8))));

    service(rec, repo, mock(EntityDictionaryPort.class)).extractAndStore(KB, List.of("甲段", "乙段"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<EntityRepository.Candidate>> cap =
        ArgumentCaptor.forClass((Class) List.class);
    org.mockito.Mockito.verify(repo).insertAll(eq(KB), cap.capture());
    assertThat(cap.getValue()).hasSize(1);
  }

  @Test
  @DisplayName("抽取器抛异常时返回 0 且不让异常冒出去（文档入库不受牵连）")
  void recognizerFailureDoesNotPropagate() {
    EntityRepository repo = mock(EntityRepository.class);
    EntityRecognizer boom =
        new EntityRecognizer() {
          @Override
          public List<Recognized> recognize(String text) {
            throw new IllegalStateException("模型没加载");
          }

          @Override
          public List<String> supportedTypes() {
            return List.of("ORG");
          }

          @Override
          public String name() {
            return "boom";
          }
        };

    int n =
        service(boom, repo, mock(EntityDictionaryPort.class)).extractAndStore(KB, List.of("文本"));

    // ★ 返回 0 而不是"假装成功"：抽取失败与"这一段真的没有实体"在返回值上一致，
    //   但日志里留了痕（#实体抽取 失败）。文档能不能用与实体抽取无关，不该被它拖累。
    assertThat(n).isZero();
    verifyNoInteractions(repo);
  }

  @Test
  @DisplayName("空输入不查库")
  void emptyInputDoesNotHitRepository() {
    EntityRepository repo = mock(EntityRepository.class);
    EntityExtractionService s =
        service(recognizerOf(Map.of()), repo, mock(EntityDictionaryPort.class));

    assertThat(s.extractAndStore(KB, List.of())).isZero();
    assertThat(s.extractAndStore(null, List.of("x"))).isZero();
    verifyNoInteractions(repo);
  }

  @Test
  @DisplayName("抽到空串名字的候选被丢弃，不给仓储")
  void blankNamesAreDropped() {
    EntityRepository repo = mock(EntityRepository.class);
    EntityRecognizer rec =
        recognizerOf(Map.of("文本", List.of(new EntityRecognizer.Recognized("ORG", "  ", 0.8))));

    int n = service(rec, repo, mock(EntityDictionaryPort.class)).extractAndStore(KB, List.of("文本"));

    assertThat(n).isZero();
    verifyNoInteractions(repo);
  }

  @Test
  @DisplayName("关系抽到之后必须写进关系表，两端是实体 id 不是名字（0.6.4 判据）")
  void relationsArePersistedWithEntityIds() {
    UUID fromId = UUID.randomUUID();
    UUID toId = UUID.randomUUID();
    UUID segmentId = UUID.randomUUID();
    EntityRepository repo = mock(EntityRepository.class);
    when(repo.insertAll(eq(KB), anyList())).thenReturn(2);
    when(repo.findIdByName(KB, "ORG", "综合部")).thenReturn(Optional.of(fromId));
    when(repo.findIdByName(KB, "ORG", "财务资产部")).thenReturn(Optional.of(toId));
    EntityRelationRepository relations = mock(EntityRelationRepository.class);

    EntityRecognizer rec =
        new EntityRecognizer() {
          @Override
          public List<Recognized> recognize(UUID kbId, String text) {
            return List.of(new Recognized("ORG", "综合部", 0.8), new Recognized("ORG", "财务资产部", 0.8));
          }

          @Override
          public List<Relation> relations(String text) {
            return List.of(new Relation("BELONGS_TO", "综合部", "财务资产部"));
          }

          @Override
          public List<String> supportedTypes() {
            return List.of("ORG");
          }

          @Override
          public String name() {
            return "fake-relations";
          }
        };

    service(rec, repo, mock(EntityDictionaryPort.class), relations)
        .extractAndStore(
            KB,
            UUID.randomUUID(),
            List.of(new EntityExtractionService.Segment(segmentId, "综合部隶属财务资产部。")));

    // ★ 断的是"落库的两端是 id、类型是受控值、取证位置没丢"——
    //   只断"被调用过"是不够的：传名字进去同样会被调用，而外键会把它拒掉。
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<EntityRelationRepository.Candidate>> captor =
        ArgumentCaptor.forClass(List.class);
    verify(relations).insertAll(eq(KB), captor.capture());
    assertThat(captor.getValue()).hasSize(1);
    EntityRelationRepository.Candidate edge = captor.getValue().get(0);
    assertThat(edge.fromEntity()).isEqualTo(fromId);
    assertThat(edge.toEntity()).isEqualTo(toId);
    assertThat(edge.type()).isEqualTo("BELONGS_TO");
    assertThat(edge.segmentId()).isEqualTo(segmentId);
  }

  @Test
  @DisplayName("关系的一端不在实体表里时只跳过这条边，不写半条")
  void relationWithMissingEndpointIsSkipped() {
    EntityRepository repo = mock(EntityRepository.class);
    when(repo.insertAll(eq(KB), anyList())).thenReturn(1);
    when(repo.findIdByName(KB, "ORG", "综合部")).thenReturn(Optional.of(UUID.randomUUID()));
    when(repo.findIdByName(KB, "ORG", "财务资产部")).thenReturn(Optional.empty());
    EntityRelationRepository relations = mock(EntityRelationRepository.class);

    EntityRecognizer rec =
        new EntityRecognizer() {
          @Override
          public List<Recognized> recognize(UUID kbId, String text) {
            return List.of(new Recognized("ORG", "综合部", 0.8));
          }

          @Override
          public List<Relation> relations(String text) {
            return List.of(new Relation("BELONGS_TO", "综合部", "财务资产部"));
          }

          @Override
          public List<String> supportedTypes() {
            return List.of("ORG");
          }

          @Override
          public String name() {
            return "fake-half";
          }
        };

    service(rec, repo, mock(EntityDictionaryPort.class), relations)
        .extractAndStore(
            KB,
            UUID.randomUUID(),
            List.of(new EntityExtractionService.Segment(UUID.randomUUID(), "综合部隶属财务资产部。")));

    // ★ 半条边（一端是 null）会撞 kb_relation 的 not-null，整批 insert 全废；
    //   跳过它则其余边照常写入 —— 这是"宁可少一条边，不可写入一条查不出端点的边"。
    verify(relations, never()).insertAll(any(), anyList());
  }
}
