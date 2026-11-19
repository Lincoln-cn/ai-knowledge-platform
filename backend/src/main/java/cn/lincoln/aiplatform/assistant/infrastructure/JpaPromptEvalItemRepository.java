package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.PromptEvalItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 题目读口。按 {@code ordinal} 升序 —— 结果表要按题的原始顺序看。 */
public interface JpaPromptEvalItemRepository extends JpaRepository<PromptEvalItem, UUID> {

  List<PromptEvalItem> findBySetIdOrderByOrdinalAsc(UUID setId);

  int countBySetId(UUID setId);
}
