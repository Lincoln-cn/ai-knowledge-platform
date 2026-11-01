package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.FileRef;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code cv_file} 的 Spring Data 接口（今天只用到继承来的 {@code save}）。 */
public interface JpaFileRefRepository extends JpaRepository<FileRef, UUID> {}
