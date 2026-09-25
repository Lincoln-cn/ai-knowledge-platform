import { ApiError, ErrorCode } from '@/api/types'
import type { ChatAttachment } from '@/api/conversations'
import type { MockRoute } from '../dispatch'
import { currentSubjects } from '../policy'
import { saveAttachment } from '../chat-store'

/**
 * 聊天附件上传（#6）。
 *
 * `POST /files` 走 multipart。mock 与真实后端同一契约：
 * 真实后端从 multipart 解析出 File，mock 从 `FormData` 里把能拿到的元数据读出来。
 *
 * 拿不到真 `File` 时**不假装上传成功**：回执里 name 用可识别的占位、size 置 0，
 * 调用方看得出这是元数据缺失，而不是一份伪造的「12KB 的合同.pdf」。
 */

/**
 * 从请求体里抽取文件元数据。
 * 优先真 `FormData`（浏览器里 `form.get('file')` 就是 `File`，name/size/type 都是真的），
 * 其次兼容 `{ file: { name, size, mime } }` 这种测试直传形状。
 */
function extractFileMeta(body: unknown): { name: string; size: number; mime?: string } | null {
  // 真 FormData：遍历 entries，取第一个像文件的值
  if (body && typeof body === 'object' && typeof (body as FormData).entries === 'function') {
    for (const [key, value] of (body as FormData).entries()) {
      if (value && typeof value === 'object' && 'name' in value && 'size' in value) {
        const f = value as File
        return {
          name: String(f.name || key),
          size: Number(f.size) || 0,
          mime: f.type ? String(f.type) : undefined
        }
      }
      if (typeof value === 'string' && value.trim()) {
        return { name: value.trim(), size: 0 }
      }
    }
    return null
  }

  // 普通对象形状（测试 / 非浏览器直传）
  const raw = body as { file?: { name?: string; size?: number; mime?: string; type?: string } } | null
  const f = raw?.file
  if (f && (f.name || f.size != null)) {
    return {
      name: String(f.name ?? '未命名文件'),
      size: Number(f.size) || 0,
      mime: f.mime ?? (f.type ? String(f.type) : undefined)
    }
  }
  return null
}

export const fileRoutes: MockRoute[] = [
  [
    'POST',
    '/files',
    ({ body, token }) => {
      // 上传是写操作：拿不到人一律 401（policy 的「登录过期」而不是静默成功）
      currentSubjects(token)

      const meta = extractFileMeta(body)
      if (!meta) {
        throw new ApiError(
          ErrorCode.NOT_FOUND,
          '未读到待上传的文件',
          '请重新选择文件后再试'
        )
      }

      const att: ChatAttachment = {
        id: `att_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`,
        name: meta.name,
        size: meta.size,
        mime: meta.mime
      }
      saveAttachment(att)
      return att
    }
  ]
]
