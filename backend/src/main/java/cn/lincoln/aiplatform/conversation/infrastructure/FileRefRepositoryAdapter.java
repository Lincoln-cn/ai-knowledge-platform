package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.FileRef;
import cn.lincoln.aiplatform.conversation.domain.FileRefRepository;
import org.springframework.stereotype.Component;

/** {@link FileRefRepository} 的 JPA 实现。 */
@Component
public class FileRefRepositoryAdapter implements FileRefRepository {

  private final JpaFileRefRepository jpa;

  public FileRefRepositoryAdapter(JpaFileRefRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public FileRef save(FileRef file) {
    return jpa.save(file);
  }
}
