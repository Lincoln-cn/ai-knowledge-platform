<script setup lang="ts">
/**
 * 工单时间线 + 补充说明 + 附件（`#356`）。
 *
 * ★ 为什么做成组件而不是直接写在详情弹窗里：同一段「过程」要在**两侧**出现 ——
 *   投诉人在「我的投诉」里查看进度、处理人在「投诉举报」里办理。抄两遍的后果是
 *   两侧对"哪些消息算补充说明、附件能不能下载"各说一套，而这正是这次要修的病。
 *
 * ★ 侧别（投诉人 / 处理人 / 系统）在每一条上都标出来：同一张单上两种口吻混成一个"操作人"，
 *   当事人会误以为处理人的话也是自己说的。
 */
import { computed, ref, watch } from 'vue'
import {
  addComplaintNote,
  downloadComplaintAttachment,
  listComplaintEvents,
  uploadComplaintAttachment,
  type ComplaintAttachment,
  type ComplaintEvent
} from '@/api/compliance'
import { MsgError, MsgSuccess } from '@/utils/message'
import { COMPLAINT_STATUS_LABELS } from '@/constants/labels'

const props = defineProps<{ ticketId: string }>()
const emit = defineEmits<{ changed: [] }>()

const events = ref<ComplaintEvent[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const note = ref('')
const sending = ref(false)
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const canSend = computed(() => note.value.trim().length > 0 && !sending.value)

async function load() {
  if (!props.ticketId) return
  loading.value = true
  loadError.value = null
  try {
    events.value = await listComplaintEvents(props.ticketId)
  } catch (error) {
    // ★ 时间线读不出来要显式说，而不是渲染一个空时间线 ——
    //   "这单没有过程"与"过程我读不到"是两件完全不同的事。
    loadError.value = error instanceof Error ? error.message : '时间线读取失败'
    events.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.ticketId, load, { immediate: true })

async function sendNote() {
  if (!canSend.value) return
  sending.value = true
  try {
    await addComplaintNote(props.ticketId, note.value.trim())
    note.value = ''
    await load()
    emit('changed')
    MsgSuccess('已补上说明')
  } catch (error) {
    MsgError(error instanceof Error ? error.message : '补充说明失败')
  } finally {
    sending.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function onFilePicked(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  target.value = ''
  if (!files.length) return
  uploading.value = true
  try {
    for (const file of files) {
      await uploadComplaintAttachment(props.ticketId, file)
    }
    await load()
    emit('changed')
    MsgSuccess(`已上传 ${files.length} 个附件`)
  } catch (error) {
    MsgError(error instanceof Error ? error.message : '附件上传失败')
  } finally {
    uploading.value = false
  }
}

async function saveAttachment(attachment: ComplaintAttachment) {
  try {
    const { blob, fileName } = await downloadComplaintAttachment(attachment)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    anchor.click()
    // 立刻回收：不回收的话每下载一次就漏一份 blob，长会话里会一直涨
    URL.revokeObjectURL(url)
  } catch (error) {
    MsgError(error instanceof Error ? error.message : '附件下载失败')
  }
}

const SIDE_LABELS: Record<string, string> = {
  REQUESTER: '投诉人',
  HANDLER: '处理人',
  SYSTEM: '系统'
}

function kindText(event: ComplaintEvent): string {
  if (event.kind === 'CREATED') return '提交了工单'
  if (event.kind === 'STATUS_CHANGED') {
    const from = event.fromStatus ? COMPLAINT_STATUS_LABELS[event.fromStatus] : '—'
    const to = event.toStatus ? COMPLAINT_STATUS_LABELS[event.toStatus] : '—'
    return `状态：${from} → ${to}`
  }
  if (event.kind === 'ATTACHMENT') return '上传了附件'
  return '补充说明'
}

function fmtTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <section class="thread">
    <header class="thread-head">
      <h3>处理过程</h3>
      <span class="micro muted">追加写：每一次流转、每一句说明都留在这里，不会互相覆盖</span>
    </header>

    <div v-if="loading" class="thread-empty micro">正在读时间线…</div>
    <div v-else-if="loadError" class="thread-error micro">
      时间线读取失败：{{ loadError }} —— <button class="link" type="button" @click="load">重试</button>
    </div>
    <div v-else-if="!events.length" class="thread-empty micro">还没有记录。</div>

    <ol v-else class="thread-list">
      <li v-for="event in events" :key="event.id" class="thread-item" :class="`side-${event.side.toLowerCase()}`">
        <div class="thread-meta">
          <span class="who">{{ SIDE_LABELS[event.side] ?? event.side }} · {{ event.actorName }}</span>
          <span class="micro muted">{{ fmtTime(event.at) }}</span>
        </div>
        <div class="thread-kind">{{ kindText(event) }}</div>
        <p v-if="event.note" class="thread-note">{{ event.note }}</p>
        <ul v-if="event.attachments.length" class="thread-files">
          <li v-for="attachment in event.attachments" :key="attachment.id">
            <button class="link" type="button" @click="saveAttachment(attachment)">
              {{ attachment.fileName }}
            </button>
            <span class="micro muted">{{ fmtSize(attachment.sizeBytes) }} · {{ SIDE_LABELS[attachment.side] ?? attachment.side }}</span>
          </li>
        </ul>
      </li>
    </ol>

    <div class="thread-compose">
      <el-input
        v-model="note"
        type="textarea"
        :rows="3"
        maxlength="1000"
        show-word-limit
        placeholder="再补一句（补充材料、说明情况）。它不会改状态，只是留在这条时间线上"
      />
      <div class="thread-actions">
        <input ref="fileInput" type="file" multiple hidden @change="onFilePicked" />
        <el-button size="small" :loading="uploading" @click="pickFile">上传截图 / 日志</el-button>
        <el-button size="small" type="primary" :loading="sending" :disabled="!canSend" @click="sendNote">
          发送说明
        </el-button>
      </div>
      <p class="micro muted thread-tip">
        单个附件上限 10 MB。状态流转请走「办理」—— 补充说明与办理是两件事，混在一起会让"谁推进了流程"变成谜。
      </p>
    </div>
  </section>
</template>

<style scoped>
.thread {
  margin-top: var(--space-md);
  border-top: 1px solid var(--color-rule);
  padding-top: var(--space-md);
}

.thread-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  margin-bottom: var(--space-sm);
}

.thread-head h3 {
  margin: 0;
  font-size: var(--text-md);
  font-weight: 600;
}

.thread-empty,
.thread-error {
  padding: var(--space-sm) 0;
}

.thread-error {
  color: var(--color-danger, #c45656);
}

.thread-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.thread-item {
  border-left: 3px solid var(--color-rule);
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-surface-sunken, rgba(0, 0, 0, 0.02));
}

/* 两种口吻用不同色条：同一张单上"我说的"和"平台答复的"要一眼分得开 */
.thread-item.side-requester {
  border-left-color: var(--color-primary, #409eff);
}

.thread-item.side-handler {
  border-left-color: var(--color-success, #67c23a);
}

.thread-meta {
  display: flex;
  justify-content: space-between;
  gap: var(--space-sm);
}

.thread-meta .who {
  font-size: var(--text-sm);
  font-weight: 600;
}

.thread-kind {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.thread-note {
  margin: var(--space-2xs) 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.thread-files {
  list-style: none;
  margin: var(--space-2xs) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.thread-compose {
  margin-top: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.thread-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-sm);
}

.thread-tip {
  margin: 0;
}

.link {
  background: none;
  border: none;
  padding: 0;
  color: var(--color-primary, #409eff);
  cursor: pointer;
  font-size: inherit;
}

.link:hover {
  text-decoration: underline;
}
</style>
