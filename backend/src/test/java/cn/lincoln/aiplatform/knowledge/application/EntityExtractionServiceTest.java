package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.EntityDictionaryPort;
import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.util.List;
import java.util.Map;
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
    return new EntityExtractionService(recognizer, repo, dict, mock(EntityMentionRepository.class));
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
}
