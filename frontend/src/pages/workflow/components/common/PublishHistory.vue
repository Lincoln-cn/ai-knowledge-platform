<template>
    <div class="workflow-publish-history">
        <!--
          面板自带关闭按钮：不再依赖父页面在某个状态下才渲染的那个 ✕。
          之前 ✕ 只在「已预览某版本」时才出现，刚打开历史时没有任何关闭入口。
        -->
        <h4 class="panel-head">
            <span>发布历史</span>
            <el-button text size="small" title="关闭" @click="emit('close')">
                <el-icon>
                    <Close />
                </el-icon>
            </el-button>
        </h4>
        <div class="list-height">
            <el-scrollbar>
                <div class="history-list">
                    <common-list :data="LogData" v-loading="loading" @click="clickListHandle"
                        @mouseenter="mouseenter" @mouseleave="mouseId = ''">
                        <template #default="{ row, index }">
                            <div class="row-between">
                                <div class="grow">
                                    <!--
                                      字段映射到契约（api/workflow.ts 的 WorkflowVersion）：
                                      `note` 发布说明 / `version` 版本号 / `publishedByName` 发布人 /
                                      `publishedAt` 发布时间 / `nodeCount` 节点数。
                                      版本号 / 时间 / 作者 / 节点数都是数据 —— 走 .num（mono + 等宽数字）。
                                      发布人显示的是**名字**，uuid 只进 title；名字解析不到（用户已删）时
                                      显示「（未知用户）」—— 不回退显示那串 uuid，那对人没有意义。
                                    -->
                                    <h5 class="flex align-center">
                                        <el-tag size="small" effect="plain"
                                            :type="index === 0 ? 'primary' : 'info'"><span class="num">v{{ row.version }}</span></el-tag>
                                        <el-tag v-if="index === 0" class="default-tag ml-4">最近发布</el-tag>
                                    </h5>
                                    <!-- 发布说明单独占行：面板只有 320px，挤在标签后面会被截成「补充风险...」 -->
                                    <div class="publish-note ellipsis-2">{{ row.note || '（无发布说明）' }}</div>
                                    <div class="publish-meta num">
                                        <AppAvatar :size="20" class="avatar-grey mr-4">
                                            <el-icon>
                                                <UserFilled />
                                            </el-icon>
                                        </AppAvatar>
                                        <span :title="row.publishedBy">{{ row.publishedByName || '（未知用户）' }}</span>
                                        <span class="meta-sep">·</span>
                                        <span>{{ datetimeFormat(row.publishedAt) }}</span>
                                        <span class="meta-sep">·</span>
                                        <span>{{ row.nodeCount }} 个节点</span>
                                    </div>
                                </div>

                                <div @click.stop v-show="mouseId === row.id">
                                    <el-dropdown trigger="click">
                                        <el-button text>
                                            <el-icon>
                                                <MoreFilled />
                                            </el-icon>
                                        </el-button>
                                        <template #dropdown>
                                            <el-dropdown-menu>
                                                <!-- <el-dropdown-item @click.stop="openEditVersion(row)">
                                                    <el-icon>
                                                        <EditPen />
                                                    </el-icon>
                                                    编辑
                                                </el-dropdown-item> -->
                                                <el-dropdown-item @click="refreshVersion(row)">
                                                    <el-icon>
                                                        <RefreshLeft />
                                                    </el-icon>
                                                    恢复此版本
                                                </el-dropdown-item>
                                            </el-dropdown-menu>
                                        </template>
                                    </el-dropdown>
                                </div>
                            </div>
                        </template>

                        <template #empty>
                            <div class="empty">
                                <div class="empty-title">暂无历史记录</div>
                            </div>
                        </template>
                    </common-list>
                </div>
            </el-scrollbar>
        </div>
    </div>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getWorkFlowVersion } from '@/api/legacy/setting'
import { datetimeFormat } from '@/utils/time'
import { MsgError } from '@/utils/message'
import { pendingEndpointHint } from '@/lib/pendingEndpoint'
const route = useRoute();
const id = route.params.appId as string

const emit = defineEmits(['click', 'refreshVersion', 'close'])
const loading = ref(false)
const LogData = ref<any[]>([])

const mouseId = ref('')

function mouseenter(row: any) {
    mouseId.value = row.id
}

function clickListHandle(item: any) {
    emit('click', item)
}

function refreshVersion(item: any) {
    emit('refreshVersion', item)
}

function getList() {
    loading.value = true
    getWorkFlowVersion(id)
        .then((res: any) => {
            LogData.value = res.data.data
        })
        .catch((error: unknown) => {
            // ★ 端点后端没实现（`#198`）：原先无 catch ⇒ 列表永远空、loading 永远在转，
            //   用户看到的是「发布历史是空的」而不是「这个功能还没接后端」（`#160`）。
            LogData.value = []
            const pending = pendingEndpointHint(error, '查看发布历史', '#198')
            MsgError(pending ?? '发布历史加载失败，请稍后重试')
        })
        .finally(() => {
            loading.value = false
        })
}

onMounted(() => {
    getList()
})
</script>
<style lang="scss" scoped>
/*
 * 定位在画布区（相对 .workflow-main），所以用 top/bottom 贴满，
 * **不用 100vh** —— 页面嵌在 shell 里，100vh 会把面板撑出可视区（这正是"显示异常"的根因）。
 * 面板自己是一个 flex 列：标题固定、列表吃掉剩余高度。
 */
.workflow-publish-history {
    width: 320px;
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
        padding: var(--space-sm) var(--space-md);
    }

    .list-height {
        flex: 1;
        min-height: 0;
    }

    .history-list {
        padding: var(--space-xs);
    }

    .publish-note {
        margin-top: var(--space-2xs);
        font-size: var(--text-xs);
        line-height: var(--leading-tight);
        color: var(--color-ink-2);
        word-break: break-word;
    }

    /* 版本号 / 时间 / 作者 / 节点数：数据行，mono + 等宽数字 */
    .publish-meta {
        display: flex;
        align-items: center;
        margin-top: var(--space-xs);
        font-size: var(--text-2xs);
        color: var(--color-muted);

        .meta-sep {
            margin: 0 var(--space-2xs);
            color: var(--color-faint);
        }
    }
}
</style>
