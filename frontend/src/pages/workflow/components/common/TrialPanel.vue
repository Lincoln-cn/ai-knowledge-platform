<template>
    <!--
      试用抽屉（`#355`）：挂在 .workflow-main 里、贴右侧，与画布并存 ——
      照 PublishHistory 的同一套定位（top/bottom 贴满，不用 100vh，页面嵌在 shell 里）。
    -->
    <aside class="workflow-trial">
        <header class="panel-head">
            <div class="head-main">
                <span class="head-title">试用</span>
                <span class="head-conv micro">临时会话 {{ shortId(conversationId) }}</span>
            </div>
            <el-button text @click="emit('close')">
                <el-icon><Close /></el-icon>
            </el-button>
        </header>

        <div ref="threadRef" class="thread">
            <p v-if="!turns.length" class="empty">
                输入一句**用户真会问的话**再回车。<br />
                每次运行都会先保存当前画布，再拿这张图跑一遍 —— 所以试的是你正在编的东西。
            </p>

            <div v-for="(turn, index) in turns" :key="index" class="turn">
                <div class="ask">{{ turn.question }}</div>

                <div class="answer" :class="{ failed: !turn.ok }">
                    <template v-if="turn.ok">
                        <p class="answer-text">{{ turn.output || '（这次没有输出）' }}</p>
                    </template>
                    <template v-else>
                        <p class="answer-text fail-title">这次没跑通</p>
                        <ul class="fail-list">
                            <li v-for="(one, i) in turn.errors ?? []" :key="i">{{ one }}</li>
                        </ul>
                    </template>

                    <div class="answer-meta micro">
                        耗时 {{ turn.elapsedMs }}ms · {{ turn.trail.length }} 个节点
                        <template v-if="turn.executionId">· 记录 {{ shortId(turn.executionId) }}</template>
                    </div>

                    <details v-if="turn.trail.length" class="trail">
                        <summary>节点轨迹</summary>
                        <ol>
                            <li v-for="(step, i) in turn.trail" :key="`${step.nodeId}-${i}`">
                                {{ step.type }}
                            </li>
                        </ol>
                    </details>
                </div>
            </div>
        </div>

        <footer class="panel-foot">
            <el-input
                v-model="question"
                :disabled="running"
                placeholder="例如：住宿标准是多少？"
                @keyup.enter="run"
            />
            <div class="foot-actions">
                <el-button type="primary" :loading="running" :disabled="!question.trim()" @click="run">
                    运行
                </el-button>
                <el-button text :disabled="!turns.length || running" @click="newSession">新会话</el-button>
                <el-button text :disabled="!turns.length || running" @click="clear">清空</el-button>
            </div>
            <p class="foot-note micro">
                ★ 这里的「多轮」只是把几次试用**归到同一个临时会话**，方便在运行记录里一起翻；
                每次执行仍是<b>独立</b>的，不带上一轮的历史。要测多轮上下文，得走应用市场里的真实对话。
            </p>
        </footer>
    </aside>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { Close } from '@element-plus/icons-vue'
import type { DebugWorkflowResult } from '@/api/workflow'

/** 一轮试用。 */
interface Turn {
    question: string
    ok: boolean
    output: string | null
    errors: string[] | null
    elapsedMs: number
    trail: DebugWorkflowResult['nodeTrail']
    executionId: string | null
}

const props = defineProps<{
    /** 由画布页提供：先保存当前画布，再用这张图跑一次。组件不管图的来源。 */
    run: (question: string, conversationId: string) => Promise<DebugWorkflowResult>
}>()

const emit = defineEmits<{ close: [] }>()

const question = ref('')
const running = ref(false)
/** 临时会话 id：一进这一批对话就定下，换会话时才重置（后端按它把运行记录归到一起）。 */
const conversationId = ref(newConversation())
const turns = ref<Turn[]>([])
const threadRef = ref<HTMLElement | null>(null)

const shortId = (id: string) => (id.length > 12 ? id.slice(0, 8) + '…' : id)

function newConversation(): string {
    return crypto.randomUUID()
}

async function scrollToEnd() {
    await nextTick()
    const box = threadRef.value
    if (box) {
        box.scrollTop = box.scrollHeight
    }
}

async function run() {
    const text = question.value.trim()
    if (!text || running.value) {
        return
    }
    running.value = true
    try {
        const result = await props.run(text, conversationId.value)
        turns.value.push({
            question: text,
            ok: result.ok,
            output: result.finalOutput,
            errors: result.errors,
            elapsedMs: result.elapsedMs,
            trail: result.nodeTrail ?? [],
            executionId: result.executionId
        })
        question.value = ''
        await scrollToEnd()
    } catch (error: unknown) {
        // 抛出来的（网络、权限、保存失败）也进对话流 —— 只在角落弹一个 toast 会让"这一轮去哪了"变成谜。
        turns.value.push({
            question: text,
            ok: false,
            output: null,
            errors: [error instanceof Error ? error.message : String(error)],
            elapsedMs: 0,
            trail: [],
            executionId: null
        })
        await scrollToEnd()
    } finally {
        running.value = false
    }
}

function newSession() {
    conversationId.value = newConversation()
    turns.value = []
}

function clear() {
    turns.value = []
}
</script>

<style lang="scss" scoped>
/*
 * 定位在画布区（相对 .workflow-main）：top/bottom 贴满，不用 100vh。
 * 面板自己是一个 flex 列：标题固定、对话流吃掉剩余高度、底部输入固定。
 */
.workflow-trial {
    width: 420px;
    position: absolute;
    right: 0;
    top: 0;
    bottom: 0;
    background: var(--color-surface);
    z-index: var(--z-raised);
    display: flex;
    flex-direction: column;
    border-left: 1px solid var(--color-rule);
    box-shadow: var(--shadow-float);

    .panel-head {
        flex: none;
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--space-sm) var(--space-md);
        border-bottom: 1px solid var(--color-rule);
    }

    .head-main {
        display: flex;
        align-items: baseline;
        gap: var(--space-sm);
        min-width: 0;
    }

    .head-title {
        font-weight: 600;
    }

    .head-conv {
        color: var(--color-text-secondary);
        font-family: var(--font-mono, monospace);
    }

    .thread {
        flex: 1;
        min-height: 0;
        overflow-y: auto;
        padding: var(--space-md);
        display: flex;
        flex-direction: column;
        gap: var(--space-md);
    }

    .empty {
        margin: 0;
        font-size: 13px;
        line-height: 1.7;
        color: var(--color-text-secondary);
    }

    .turn {
        display: flex;
        flex-direction: column;
        gap: var(--space-xs);
    }

    .ask {
        align-self: flex-end;
        max-width: 88%;
        padding: var(--space-xs) var(--space-sm);
        border-radius: var(--radius-md);
        background: var(--color-primary-bg, #eff6ff);
        font-size: 13px;
        line-height: 1.6;
        white-space: pre-wrap;
        word-break: break-word;
    }

    .answer {
        max-width: 100%;
        padding: var(--space-sm);
        border: 1px solid var(--color-rule);
        border-radius: var(--radius-md);
        background: var(--color-surface-2, #fafafa);
    }

    .answer.failed {
        border-color: var(--color-danger, #b91c1c);
        background: var(--color-danger-bg, #fef2f2);
    }

    .answer-text {
        margin: 0;
        font-size: 13px;
        line-height: 1.7;
        white-space: pre-wrap;
        word-break: break-word;
    }

    .fail-title {
        font-weight: 600;
        color: var(--color-danger, #b91c1c);
    }

    .fail-list {
        margin: var(--space-xs) 0 0;
        padding-left: 18px;
        font-size: 12px;
        line-height: 1.7;
        color: var(--color-danger, #b91c1c);
    }

    .answer-meta {
        margin-top: var(--space-xs);
        color: var(--color-text-secondary);
    }

    .trail {
        margin-top: var(--space-xs);
        font-size: 12px;

        summary {
            cursor: pointer;
            color: var(--color-text-secondary);
        }

        ol {
            margin: var(--space-2xs) 0 0;
            padding-left: 18px;
            line-height: 1.7;
        }
    }

    .panel-foot {
        flex: none;
        padding: var(--space-sm) var(--space-md);
        border-top: 1px solid var(--color-rule);
        display: flex;
        flex-direction: column;
        gap: var(--space-sm);
    }

    .foot-actions {
        display: flex;
        gap: var(--space-xs);
    }

    .foot-note {
        margin: 0;
        color: var(--color-text-secondary);
        line-height: 1.6;
    }
}
</style>
