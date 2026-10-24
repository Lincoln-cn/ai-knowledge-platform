package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.application.QaPairService;
import cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.feedback.FeedbackPromotionPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 反馈沉淀到知识库问答对的实现（#208）。
 *
 * <p>复用 {@link QaPairService#importPairs}：库可见性、密级 ceiling、出处归属都沿用既有校验， origin 固定 {@link
 * QaPairOrigin#FEEDBACK}；本适配器只补“从会话取密级”和幂等键两件事。
 */
@Component
class FeedbackPromotionAdapter implements FeedbackPromotionPort {

  private static final Logger log = LoggerFactory.getLogger(FeedbackPromotionAdapter.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final QaPairService qaPairs;

  FeedbackPromotionAdapter(NamedParameterJdbcTemplate jdbc, QaPairService qaPairs) {
    this.jdbc = jdbc;
    this.qaPairs = qaPairs;
  }

  @Override
  public void promote(
      UUID knowledgeBaseId,
      UUID feedbackMessageId,
      UUID conversationId,
      String question,
      String answer) {
    String sourceRef = "feedback:" + feedbackMessageId;
    Integer existing =
        jdbc.queryForObject(
            """
            select count(*) from kb_qa_pair
             where kb_id = :kb and source_ref = :ref and deleted_at is null
            """,
            Map.of("kb", knowledgeBaseId, "ref", sourceRef),
            Integer.class);
    if (existing != null && existing > 0) {
      return;
    }
    String classification =
        jdbc.queryForObject(
            "select max_classification from cv_conversation where id = :id",
            Map.of("id", conversationId),
            String.class);
    qaPairs.importPairs(
        knowledgeBaseId,
        List.of(
            new QaPairService.ImportPair(
                question,
                answer,
                null,
                ClassificationLevel.valueOf(classification),
                QaPairOrigin.FEEDBACK,
                sourceRef)),
        null);
    log.info(
        "反馈沉淀为问答对：feedback={} kb={} qaPairOrigin=FEEDBACK", feedbackMessageId, knowledgeBaseId);
  }
}
