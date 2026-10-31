package cn.lincoln.aiplatform.platform.domain;

/**
 * 文件字节的存放端口（{@code 08 §4.9}：本地磁盘起步，将来换对象存储<strong>不动调用方</strong>）。
 *
 * <h3>为什么端口里只有「存」没有「读」</h3>
 *
 * <p>今天唯一的消费者是 {@code POST /files}（附件上传），它只写不读 —— 读的人要等到"附件真的进检索/解析"那一批。 现在就开一个 {@code
 * read}，等于留一条没人走的缝：本项目已经有"留了数据层却被读成已接入"的前车之鉴（{@code #78} 判据 ③、 {@code #86} 的 {@code revision}
 * 列）。<strong>换对象存储时再加 {@code read}，那时它才第一次有意义。</strong>
 *
 * <h3>★ 键与名字是两件事</h3>
 *
 * <p>实现返回的 {@code key} 是<strong>自己生成的</strong>，绝不使用调用方给的原始文件名：原始名可以是 {@code
 * ../../etc/passwd}、可以带换行、可以 200 个字。原始名只落到数据库的 {@code name} 列用于展示， <strong>不参与定位文件</strong> ——
 * 否则这个端口就有了路径穿越的攻击面。
 */
public interface FileStorePort {

  /**
   * 把字节写到存储里，返回定位它用的键。
   *
   * <p>调用方可以假定：写成功之前抛异常、写成功之后键可用；键在整个存储里唯一。
   *
   * @param originalName 原始文件名，**只用来取扩展名**（读不出扩展名时返回不带扩展名的键）
   * @param content 文件内容，调用方已确认非空
   * @return 相对键，落地到 {@code cv_file.storage_key}
   * @throws IllegalStateException 写盘失败（磁盘满、只读、目录建不出来）—— 这一层不吞：上传失败却拿到 200， 用户随后发一条指向空气的消息才是真正的故障
   */
  String store(String originalName, byte[] content);
}
