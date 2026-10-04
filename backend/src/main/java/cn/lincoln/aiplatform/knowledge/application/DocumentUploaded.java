package cn.lincoln.aiplatform.knowledge.application;

import java.util.UUID;

/**
 * 「这份文档带字节上传了，去解析它」（第 9 周 · 0.3.1）。
 *
 * <h3>★ 为什么用事件，而不是让 {@code DocumentService} 直接调 {@code ParseRunner}</h3>
 *
 * <p>解析要跑在**事务提交之后**、并且要异步：扫描件实测每页约 3.6 秒，同步跑会把上传请求挂住几十秒到几分钟 （网关超时、用户以为卡死、进度也看不见）。而 {@code
 * finishParse} 又要落回 {@code DocumentService} （分块与落库的逻辑在那里）—— 两者互相持有就是构造依赖环。
 *
 * <p>⇒ 一路下来：{@code DocumentService} <b>发事件</b>（它不认识 ParseRunner）， {@code ParseRunner} 用
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 收 ⇒ 依赖是单向的，而且「事务提交后才起任务」这条纪律由框架保证，不靠人记得写。
 *
 * <p>★ 这一条是有来历的：项目踩过一次反面（issue #18）—— 后台任务在调用方事务提交前就起来了， 于是它读到的是**提交前**的状态，一次本该成功的重算变成了失败。
 *
 * @param documentId 文档 id
 * @param content 原始文件字节（★ 只在内存里传递：本版不落原件，见 {@code DocumentService} 的说明）
 * @param filename 原始文件名（格式白名单与错误文案都用它）
 * @param userId 建档人（分段落库要记 created_by）
 */
public record DocumentUploaded(UUID documentId, byte[] content, String filename, UUID userId) {}
