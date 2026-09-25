import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 消息提示的薄封装。
 *
 * 保留旧名字（`MsgSuccess` 而不是直接调 `ElMessage`）是有意的：
 * 编排画布是从旧工程整体搬过来的，成百处调用点不必跟着改 ——
 * 搬迁期的改动面越小越好，**这一层是"调用点不改、行为统一"的收口点**。
 */

export const MsgSuccess = (message: string) =>
  ElMessage({ message, type: 'success', showClose: true, duration: 1500 })

export const MsgInfo = (message: string) =>
  ElMessage({ message, type: 'info', showClose: true, duration: 1500 })

export const MsgWarning = (message: string) =>
  ElMessage({ message, type: 'warning', showClose: true, duration: 1500 })

export const MsgError = (message: string) =>
  ElMessage({ message, type: 'error', showClose: true, duration: 1500 })

export const MsgAlert = (title: string, description: string, options?: Record<string, unknown>) =>
  ElMessageBox.alert(description, title, { confirmButtonText: '确定', ...options })

export const MsgConfirm = (title: string, description: string, options?: Record<string, unknown>) =>
  ElMessageBox.confirm(description, title, {
    showCancelButton: true,
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    ...options
  })
