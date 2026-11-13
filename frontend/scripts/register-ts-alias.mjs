/**
 * 注册 `@/` 别名解析器，供 `node --import ./scripts/register-ts-alias.mjs --test ...` 使用。
 *
 * <p>与 `settings.ts` 同样只做最小一件事：把 loader 挂上。写成单独文件是因为
 * `module.register` 必须在模块顶层执行、且要在被测模块被加载之前完成。
 */
import { register } from 'node:module'

register('./ts-alias-loader.mjs', import.meta.url)
