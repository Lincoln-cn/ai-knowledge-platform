package cn.lincoln.aiplatform.platform.dictionary;

/**
 * 一条字典项（{@code 08 §十}）。
 *
 * <p>★ 对外只有这一个形状：<strong>业务键是 {@code (kind, code)}</strong>（库里有唯一约束）， 所以改与停用也按 {@code (kind, code)}
 * 定位 —— 不必把行 id 漏到契约里。 少一个 id 就少一处"拿 id 当业务键"的机会（那是本项目在别处踩过的坑）。
 *
 * @param kind 所属种类
 * @param code 机器码 —— ★ 它是历史数据、召回前缀、{@code content_hash} 里已经用着的那个值（见 {@link DictionaryKind}）
 * @param label 展示文案：可随时改，<strong>不进库、不进前缀</strong>（改了不会触发重新向量化）
 * @param sort 排序（下拉顺序）
 * @param enabled 停用 = 不再出现在新的下拉里；既有数据照旧可读
 * @param remark 备注（维护页给管理员看的说明）
 */
public record DictionaryItem(
    DictionaryKind kind, String code, String label, int sort, boolean enabled, String remark) {}
