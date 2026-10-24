package cn.lincoln.aiplatform.knowledge.application;

import java.util.List;

/**
 * 上传策略（契约 {@code UploadPolicy}）。
 *
 * <p>前端在打开上传对话框时先取这一档，再决定可以选什么、选多少、单个多大；因此在服务端给出， 而不是让每个页面各写一份常量。
 *
 * <p>★ 当前只暴露平台级上限：{@code spring.servlet.multipart.max-file-size=100MB} 是硬边界，
 * 更小的库级配额还没有数据模型；等配额落地时在这里加字段即可。
 */
public record UploadPolicyView(
    int maxFileSizeMB, int maxFileCount, List<String> acceptedExtensions) {}
