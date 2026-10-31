package cn.lincoln.aiplatform.conversation.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * 上传成功后的附件视图（契约 {@code ChatAttachment}：{@code {id, name, size, mime?}}）。
 *
 * <p>★ {@code mime} 在缺失时<strong>不输出键</strong>而不是输出 {@code null}：契约里它是可选的，前端拿 {@code null} 会渲染成
 * "null" 或者走到一个没测过的分支。
 *
 * <p>★ 刻意<strong>不</strong>回 {@code storageKey}：存储键是服务端寻址用的，回给前端等于把"文件系统布局"写进接口， 换对象存储那天就是个破坏性变更。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileView(UUID id, String name, long size, String mime) {}
