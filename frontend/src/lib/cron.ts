/**
 * cron 表达式的格式检查（只回答「这个字符串能不能被读懂」）。
 *
 * ★ 为什么是这套规则：后端两处 cron 最终都交给同一个解析器 ——
 *   `org.springframework.scheduling.support.CronExpression`：
 *   - 记忆做梦（`cv_memory_setting.dream_cron`，默认 `0 40 3 * * *`，运行期可改）用的是**六段式**（秒 分 时 日 月 周）；
 *   - 应用定时触发（`backend/.../assistant/application/TriggerCron.java`）**对外收 5 段**，
 *     内部拼上 `"0 "` 前缀再交给同一个类。
 *   所以这里实现的是 Spring 六段式；传 `fields: 5` 时按「省略秒位」处理，与 TriggerCron 一致。
 *
 * ★ 与 Quartz 的差别（别混）：Quartz 有**第七段「年」**，Spring 没有。
 *   把 Quartz 的 7 段表达式贴进来会在这里被判不合法，这是对的。
 *   `?`（不指定）两者都接受，本文按 Spring 的语义允许它只出现在「日」「周」两段。
 *
 * ★ 它只做"格式对不对"，不做"这个时间点合不合理"（例如 `0 0 30 2 * *` 是合法的写法，
 *   但 2 月没有 30 号 —— 那属于"永远不触发"，由后端 `CronExpression.next()` 返回空来体现）。
 */

const FIELD_NAMES = ['秒', '分', '时', '日', '月', '周'] as const;

/** 每个字段的闭区间 */
const FIELD_RANGES: [number, number][] = [
  [0, 59],
  [0, 59],
  [0, 23],
  [1, 31],
  [1, 12],
  [0, 7] // 0 与 7 都是周日
];

const MONTH_NAMES = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
const DAY_NAMES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];

/** 字段名或数字 → 数字；看不懂返回 null */
function numberOf(token: string, index: number): number | null {
  if (/^\d+$/.test(token)) return Number(token);
  const upper = token.toUpperCase();
  if (index === 4) {
    const i = MONTH_NAMES.indexOf(upper);
    return i >= 0 ? i + 1 : null;
  }
  if (index === 5) {
    const i = DAY_NAMES.indexOf(upper);
    return i >= 0 ? i : null;
  }
  return null;
}

function checkItem(item: string, index: number): string | null {
  const [min, max] = FIELD_RANGES[index];
  const isDay = index === 3;
  const isWeek = index === 5;

  // 步长：base/step，只允许一个斜杠
  const stepParts = item.split('/');
  if (stepParts.length > 2) return `多了一个「/」：${item}`;
  const base = stepParts[0];
  if (stepParts.length === 2) {
    const step = stepParts[1];
    if (!/^\d+$/.test(step) || Number(step) < 1) return `步长要是正整数：${item}`;
  }

  if (base === '*' || base === '?') {
    if (base === '?' && !(isDay || isWeek)) return `只有「日」和「周」能用「?」：${item}`;
    return null;
  }

  // L / L-n：只对「日」「周」有意义
  if (/^L$/i.test(base)) return isDay || isWeek ? null : `「L」只能用在「日」或「周」：${item}`;
  if (/^L-\d+$/i.test(base)) return isDay ? null : `「L-n」只能用在「日」：${item}`;
  if (isWeek && /^[0-7]L$/i.test(base)) return null;

  // 15W：本月第 15 个工作日（只对「日」）
  if (/^\d+W$/i.test(base)) {
    if (!isDay) return `「W」只能用在「日」：${item}`;
    const n = Number(base.slice(0, -1));
    return n >= 1 && n <= 31 ? null : `第 ${n} 天不存在：${item}`;
  }

  // 3#2：第三个周一（只对「周」）
  if (base.includes('#')) {
    const hashParts = base.split('#');
    if (hashParts.length > 2) return `多了一个「#」：${item}`;
    if (!isWeek) return `「#」只能用在「周」：${item}`;
    const day = numberOf(hashParts[0], index);
    if (day === null || day < 0 || day > 7) return `「#」前面要写星期几：${item}`;
    const nth = Number(hashParts[1]);
    if (!Number.isInteger(nth) || nth < 1 || nth > 5) return `「#」后面要是 1–5：${item}`;
    return null;
  }

  // 范围 a-b
  const rangeParts = base.split('-');
  if (rangeParts.length > 2) return `多了一个「-」：${item}`;
  if (rangeParts.length === 2) {
    const from = numberOf(rangeParts[0], index);
    const to = numberOf(rangeParts[1], index);
    if (from === null) return `范围起点看不懂：${item}`;
    if (to === null) return `范围终点看不懂：${item}`;
    if (from > to) return `范围起点比终点大：${item}`;
    if (from < min || to > max) return `范围要在 ${min}–${max} 之间：${item}`;
    return null;
  }

  const value = numberOf(base, index);
  if (value === null) return `看不懂：${item}`;
  if (value < min || value > max) return `要在 ${min}–${max} 之间：${item}`;
  return null;
}

/**
 * 返回**给人看的错误文案**；合法时返回 `null`（与后端 `TriggerCron.validate` 的形状一致：
 * 它返回 `Optional<String>`，也是"能解析就空"）。
 *
 * @param expr cron 表达式
 * @param fields 段数：记忆做梦是 6（Spring 原生），应用定时触发是 5（内部补秒位）
 */
export function validateCron(expr: string, fields: 5 | 6 = 6): string | null {
  const raw = (expr ?? '').trim();
  if (!raw) return '不能为空';

  const parts = raw.split(/\s+/);
  if (parts.length !== fields) {
    return fields === 6
      ? `要 6 段（秒 分 时 日 月 周），现在写了 ${parts.length} 段`
      : `要 5 段（分 时 日 月 周），现在写了 ${parts.length} 段`;
  }

  // 5 段时第 0 个位置是「分」，跳过「秒」
  const start = fields === 5 ? 1 : 0;
  for (let i = start; i < parts.length; i += 1) {
    // 一段里可以用逗号列多个值（`1,15,30`），逐个查
    const items = parts[i].split(',');
    if (items.some((item) => item === '')) return `${FIELD_NAMES[i]}：有多余的逗号（${parts[i]}）`;
    for (const item of items) {
      const err = checkItem(item, i);
      if (err) return `${FIELD_NAMES[i]}：${err}`;
    }
  }
  return null;
}
