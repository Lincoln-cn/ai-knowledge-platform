package cn.lincoln.aiplatform.platform.domain;

/**
 * 文件字节的存放端口（{@code 08 §4.9}：本地磁盘起步，将来换对象存储<strong>不动调用方</strong>）。
 *
 * <h3>★ 为什么先只有「存」，后来才加「读」（{@code #187} 判据 4）</h3>
 *
 * <p>{@code POST /files} 落地那一批唯一的消费者是上传本身，它只写不读 —— 所以那一批的端口里<strong>刻意</strong>没有 {@code
 * read}：读了没人走的缝，就会被后来的读者当成"已经有人读过了"（本项目前车之鉴：{@code #78} 判据 ③、{@code #86} 的 {@code revision} 列）。
 *
 * <p>★★ 现在它来了：附件要<strong>真的进检索</strong>就必须把字节重新读出来解析（{@code 13 §11.4.1}）。因此本批新增 {@link
 * #read}，它的存在本身就是"会话附件进检索"这一步的痕迹。
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

  /**
   * 按 {@code store} 返回的键把字节读回来（{@code #187} 判据 4 的入口）。
   *
   * <p>★ <strong>失败一律抛，不返回空数组</strong>：返回 {@code new byte[0]} 会让"文件不在了"与"文件是空的"看起来一样，
   * 而前者在问答侧的症状是"附件一个字都检索不到"，后者是"上传时就被拒了" —— 两种完全不同的故障，不能共用一个读数。
   *
   * @param key {@code store} 返回的键（相对键，不是绝对路径）
   * @return 文件字节（非空）
   * @throws IllegalStateException 键不存在 / 读失败 / 键解析后落在存储根之外
   */
  byte[] read(String key);
}
