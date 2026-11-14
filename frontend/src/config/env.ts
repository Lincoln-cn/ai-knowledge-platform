/** 运行期配置。改后端地址只改 .env，不在代码里写死。 */
export const APP_TITLE = import.meta.env.VITE_APP_TITLE || '知识助理平台'

/** API 前缀，与后端约定为 /api/v1 */
export const API_PREFIX = import.meta.env.VITE_API_BASE || '/api/v1'

/**
 * 是否走 mock。
 * 接口契约已固定（src/api/**），mock 只是传输层的一种实现：
 * 置 false 即切到真实后端，调用方代码一行不改。
 */
export const USE_MOCK = (import.meta.env.VITE_USE_MOCK ?? 'true') !== 'false'

/**
 * 「当前数据从哪来」的对外说法 —— **只此一处**。
 *
 * ★ 为什么要有它：`.env.development` 的默认值是 `VITE_USE_MOCK=true`，于是
 *   `npm run dev` 打开的界面全跑在 `src/mock/**` 的夹具上，而**界面本身毫无提示**。
 *   读数是"能用"，真因是"夹具能用"—— 这是 silent-failure 的教科书形状（`#340`）。
 *
 * 登录页早就吃过同一条亏（那里曾写死「当前走 mock」，切真后端后界面在说谎），
 * 修法是把文案与 `USE_MOCK` 同源。现在把它推广到外壳：顶栏常驻一个角标，
 * 所以**登录之后也看得见**，不再只在登录页底部说一次。
 *
 * 判据（`scripts/check-data-mode.mjs` 扫源码）：两处都从本常量取，不许各写一份。
 */
export const DATA_MODE_TEXT = USE_MOCK ? '当前走 mock' : '当前连的是真后端'

/** 角标上的短标签。刻意不叫「mock」—— 英文缩写在这个位置上没人知道它是什么意思。 */
export const DATA_MODE_BADGE = '模拟数据'

/** 悬停/读屏用的解释：说清现状 + 怎么切回去，不要求读者去翻文档。 */
export const DATA_MODE_HINT = USE_MOCK
  ? '界面数据来自前端夹具（src/mock/**），不是后端。要连真后端：把 frontend/.env.development 的 VITE_USE_MOCK 设成 false，再重起 dev server。'
  : '界面数据来自后端 /api/v1。'
