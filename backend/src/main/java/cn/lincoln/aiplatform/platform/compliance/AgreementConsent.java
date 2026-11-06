package cn.lincoln.aiplatform.platform.compliance;

import java.time.Instant;
import java.util.UUID;

/**
 * 服务协议同意留痕（{@code #83} 第九条 / 第十条）。
 *
 * <p>★ 记的是「谁、在什么时候、对哪一版协议点了同意」。协议正文本身是前端常量（产品文本， 版本化发布）；这里只留版本号与时刻 —— 正文入库会把「改一个字」变成数据迁移。
 *
 * @param id 记录 id
 * @param userId 同意人
 * @param version 协议版本号（与前端 {@code AGREEMENT_VERSION} 对齐）
 * @param agreedAt 同意时刻
 */
public record AgreementConsent(UUID id, UUID userId, String version, Instant agreedAt) {}
