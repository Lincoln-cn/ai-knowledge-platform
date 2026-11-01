package cn.lincoln.aiplatform.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 一份会话附件的元数据（{@code cv_file}）。字节不在库里，在 {@code FileStorePort} 后面。
 *
 * <h3>★ 它是什么、不是什么</h3>
 *
 * <p>它<strong>是</strong>「这个名字、这个大小、这份 mime 的字节存在某处」的一条记录，{@link #storageKey()} 指向那份字节。
 * 它<strong>不是</strong>消息内容，也不是检索单元：附件要真的参与问答，得被<em>绑定</em>到一条消息并落成 {@code kb_document}（{@code
 * scope_kind='CONVERSATION'}）—— 那是另一批（{@code 13 §11.2–11.4.1}）。
 *
 * <p>★ 所以今天的诚实边界是：{@code POST /files} 把字节存好、把元数据记下、返回一个 id；<strong>没有任何代码消费那个 id</strong>。
 * 这条边界必须写在看得见的地方（本注释 + 迁移注释 + 上传端点的 javadoc），否则下一个人会把"能上传"读成"附件能用了"。
 */
@Entity
@Table(name = "cv_file")
public class FileRef {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "mime")
  private String mime;

  @Column(name = "storage_key", nullable = false)
  private String storageKey;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected FileRef() {}

  public FileRef(
      UUID id,
      String name,
      long sizeBytes,
      String mime,
      String storageKey,
      UUID ownerId,
      Instant createdAt) {
    this.id = id;
    this.name = name;
    this.sizeBytes = sizeBytes;
    this.mime = mime;
    this.storageKey = storageKey;
    this.ownerId = ownerId;
    this.createdAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  /** 原始文件名（已去掉路径与控制字符），只用于展示。 */
  public String name() {
    return name;
  }

  public long sizeBytes() {
    return sizeBytes;
  }

  /** 上传时声明的 mime，可能为空（浏览器对 {@code .md} 之类常给空）。 */
  public String mime() {
    return mime;
  }

  public String storageKey() {
    return storageKey;
  }

  public UUID ownerId() {
    return ownerId;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
