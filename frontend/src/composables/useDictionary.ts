import { computed, ref } from 'vue'
import { dictionariesApi } from '@/api'
import type { DictionaryItem, DictionaryKind } from '@/api/dictionaries'
import { ApiError } from '@/api/types'

/**
 * 读一类基础数据字典（`08 §十`）—— 建档 / 上传 / 设置里的下拉都从这里取，不再用前端常量。
 *
 * ★ 为什么抽成一个：同一段"读 → 失败怎么办"今天要在三个组件、六个下拉里写（文种 / 密级 / 库分类）。
 *   各写一遍的必然结果是**总有一处把失败吞成空列表**，而空下拉与"这一类真的没配"长得一模一样 ——
 *   那正是本模块要消灭的状态。所以阈值是：一个 kind 被两处以上消费，就该有一个出口。
 *
 * ★ 不自动加载：调用方各有各的时机（对话框是 `open()`，面板是 `onMounted`），
 *   而"对话框打开之前读到的"字典可能已经被管理员改过 —— 建档的人必须看到当下的那一份（判据 1、2）。
 *
 * @param kind  字典种类
 * @param label 这一类在界面上的名字（"文种"/"密级"…）。由调用方给，提示文案才不会在几处各写一份
 */
export function useDictionary(kind: DictionaryKind, label: string) {
  /** ★ 只有启用项（读接口默认不带停用项）：停用的不再出现在新的下拉里，既有数据照旧可读 */
  const items = ref<DictionaryItem[]>([])
  const loading = ref(false)
  const error = ref('')

  /**
   * 空下拉的两种因必须分得开，所以这里给的是**该说什么**，而不是一个布尔：
   *   - 没读到（网络 / 权限 / 接口挂了）⇒ 说清失败，可重试；
   *   - 读到了但这一类没有启用项 ⇒ 说清"得先去基础数据里配"。
   * 两者的界面表现完全一样（一个空下拉），只有文案能把它们分开。
   */
  const hint = computed(() => {
    if (error.value) return error.value
    if (loading.value || items.value.length) return ''
    return `${label}字典里没有启用项 —— 请在「基础数据」里新增后再来`
  })

  const load = async () => {
    loading.value = true
    error.value = ''
    try {
      items.value = await dictionariesApi.listDictionaries(kind)
    } catch (e) {
      // ★ 失败时**清空**上一次的结果：留着旧列表会让人以为"字典就这几项"，
      //   而管理员可能刚停用过其中一项（停用的意义就是让新数据选不到它）。
      items.value = []
      error.value = `${label}加载失败：${e instanceof ApiError ? e.message : '请稍后重试'}`
    } finally {
      loading.value = false
    }
  }

  return { items, hint, load }
}
