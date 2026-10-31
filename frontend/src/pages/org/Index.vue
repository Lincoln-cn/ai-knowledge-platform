<template>
  <div class="org-page">
    <PageHeader title="组织机构" desc="部门树 —— 数据权限的载体（共享给部门、按部门筛文档）" />

    <div class="org-split">
      <!-- 左：树（默认全展开：部门层级通常不深，收起来反而多一次点击） -->
      <div class="panel tree-panel">
        <div class="panel-hd">
          <span class="h4">部门</span>
          <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          <el-button v-if="canManage" size="small" type="primary" @click="openCreate(null)">
            新增根部门
          </el-button>
        </div>
        <div v-if="hint" class="notice warn">{{ hint }}</div>
        <el-tree
          ref="treeRef"
          v-loading="loading"
          :data="tree"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          default-expand-all
          highlight-current
          :expand-on-click-node="false"
          @node-click="onSelect"
        >
          <template #default="{ data }">
            <span class="node" :class="{ off: !data.enabled }">
              <span class="node-name">{{ data.name }}</span>
              <code class="micro muted">{{ data.code }}</code>
              <el-tag v-if="!data.enabled" size="small" type="info" effect="plain">已停用</el-tag>
            </span>
          </template>
        </el-tree>
        <div v-if="!loading && !tree.length" class="micro muted">还没有部门，先建一个根部门</div>
      </div>

      <!-- 右：选中节点的编辑（★ 展示名与 code 分开展示，理由见下面那句提示） -->
      <div class="panel detail-panel">
        <div v-if="!selected" class="micro muted">在左侧选一个部门</div>
        <template v-else>
          <div class="panel-hd">
            <span class="h4">{{ selected.name }}</span>
            <code class="micro muted">{{ selected.code }}</code>
          </div>

          <!-- ★ 身份与表达必须分开（用户 2026-10-08 订正）：可变的东西不许参与身份判定。
               初稿这里写的是"改名要重新向量化"，那是把可变展示名当成了身份的一部分 —— 方向错了。 -->
          <div class="notice">
            展示名只用于显示，<code>code</code> 才是身份且<strong>不可改</strong>（共享主体与审计里用它）。
            展示名可改，但<strong>同级不能重名</strong> —— 重名会让"按名字比较"的地方把两个部门混成一个，
            表现为一个部门看到另一个部门的文档，且不报错。权限判定一律走 <code>code</code> 与 <code>path</code>，
            与展示名无关。已入库的分段里仍留着旧名字（那是入库时的快照），新入库的用新名字。
          </div>

          <el-form label-width="88px" class="detail-form">
            <el-form-item label="展示名">
              <el-input v-model="form.name" :disabled="!canManage" maxlength="64" />
            </el-form-item>
            <el-form-item label="排序">
              <el-input-number v-model="form.sort" :disabled="!canManage" :min="0" />
            </el-form-item>
            <el-form-item label="状态">
              <el-switch
                v-model="form.enabled"
                :disabled="!canManage"
                active-text="启用"
                inactive-text="停用"
              />
            </el-form-item>
            <el-form-item>
              <el-button v-if="canManage" type="primary" :loading="saving" @click="save">
                保存
              </el-button>
              <el-button v-if="canManage" @click="openCreate(selected.id)">新增下级</el-button>
              <el-button v-if="canManage" @click="openMove">移动…</el-button>
              <el-button v-if="canManage" type="danger" plain @click="remove">删除</el-button>
            </el-form-item>
          </el-form>
        </template>
      </div>
    </div>

    <!-- 新增（根 / 下级共用） -->
    <el-dialog v-model="createVisible" :title="createParent ? `在「${createParent.name}」下新增部门` : '新增根部门'" width="480px">
      <el-form ref="createFormRef" :model="createForm" :rules="rules" label-width="88px">
        <el-form-item label="部门编码" prop="code">
          <el-input v-model="createForm.code" placeholder="如 PURCHASING（字母/数字/下划线）" />
        </el-form-item>
        <el-form-item label="展示名" prop="name">
          <el-input v-model="createForm.name" placeholder="如 采购部" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="createForm.sort" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 移动 -->
    <el-dialog v-model="moveVisible" title="移动部门" width="480px">
      <div class="micro muted">目标上级（不选 = 移到根）</div>
      <!-- ★ 用**树选择**而不是扁平下拉（用户 2026-10-08）：目标本身就按分支组织，层级一多，
           扁平列表既看不出"这个部门在哪个分支下"，也要肉眼扫全表才敢点。
           ★ 候选里**已经排除**"自己和自己的子树"（见 moveTree）—— 那些目标注定被后端拒，
             提前摘掉比让用户点了再吃一个错误好。 -->
      <el-tree-select
        v-model="moveTarget"
        :data="moveTree"
        node-key="id"
        :props="{ label: 'label', children: 'children' }"
        check-strictly
        :render-after-expand="false"
        default-expand-all
        clearable
        placeholder="移到根（成为顶级部门）"
        style="width: 100%"
      />
      <template #footer>
        <el-button @click="moveVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitMove">移动</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { orgsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { OrgNodeView, OrgView, UpsertOrgBody } from '@/api/orgs'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const canManage = computed(() => session.can(PERM.orgManage))

const tree = ref<OrgNodeView[]>([])
const flat = ref<OrgView[]>([])
const loading = ref(false)
const saving = ref(false)
const hint = ref('')
const selected = ref<OrgView | null>(null)

const treeRef = ref()
const form = reactive({ name: '', sort: 0, enabled: true })

const createVisible = ref(false)
const createParent = ref<OrgView | null>(null)
const createFormRef = ref<FormInstance>()
const createForm = reactive({ code: '', name: '', sort: 0 })

const moveVisible = ref(false)
const moveTarget = ref<string | null>(null)

const rules: FormRules = {
  code: [
    { required: true, message: '请输入部门编码', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]{1,64}$/,
      message: '只允许字母、数字与下划线（≤64 字）',
      trigger: 'blur'
    }
  ],
  name: [{ required: true, message: '请输入展示名', trigger: 'blur' }]
}

const load = async () => {
  loading.value = true
  hint.value = ''
  try {
    const [t, l] = await Promise.all([orgsApi.orgTree(), orgsApi.listOrgs()])
    tree.value = t
    flat.value = l
    // 选中项跟着刷新（改名/移动后要看到新值）—— 找不到就清空，避免"编辑一个已经不存在的部门"
    if (selected.value) {
      selected.value = l.find((o) => o.id === selected.value?.id) ?? null
    }
  } catch (error) {
    // ★ 加载失败要留下痕迹：空树与"真的没有部门"长得一样（本项目反复踩过的静默）
    hint.value = error instanceof ApiError ? error.message : '加载组织机构失败'
  } finally {
    loading.value = false
  }
}

const onSelect = (node: OrgNodeView) => {
  selected.value = flat.value.find((o) => o.id === node.id) ?? null
  if (selected.value) {
    form.name = selected.value.name
    form.sort = selected.value.sort
    form.enabled = selected.value.enabled
  }
}

/**
 * 移动的目标候选 = 所有部门 **减去自己和自己的下级**。
 *
 * <p>★ 为什么在前端也要拦一次：后端会 400（`OrgService.move` 明确拒绝了成环），
 * 但"选一个必然失败的目标"是替用户制造错误。判据用物化路径前缀 ——
 * 这与后端"含下级"的判定**同一口径**（`07 §10.2`）。
 */
const moveOptions = computed(() => {
  const me = selected.value
  if (!me) return flat.value
  return flat.value.filter((o) => !o.path.startsWith(me.path))
})

/**
 * 移动目标树的节点形态。
 *
 * <p>★ 为什么要树（用户 2026-10-08）：候选本身就是按分支组织的，扁平列表在层级多时
 *   既看不出"这个部门在哪个分支下"，也要肉眼扫全表才敢点。
 */
interface MoveNode {
  id: string
  parentId: string | null
  label: string
  children: MoveNode[]
}

const moveTree = computed<MoveNode[]>(() => {
  const nodes = new Map<string, MoveNode>(
    moveOptions.value.map((o) => [
      o.id,
      {
        id: o.id,
        parentId: o.parentId,
        label: `${o.name}（${o.code}）`,
        children: [] as MoveNode[]
      } satisfies MoveNode
    ])
  )
  const roots: MoveNode[] = []
  // ★ moveOptions 剪掉的是"自己和自己的子树"，它们全是 me 的后代 ⇒ 剩下的集合里不会出现孤儿。
  //   仍按"父不在集合里就当根"处理：将来若把集合改成别的形状，节点只会换个位置，不会静默消失。
  for (const o of moveOptions.value) {
    const node = nodes.get(o.id)
    if (!node) continue
    const parent = o.parentId ? nodes.get(o.parentId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
})

const openCreate = (parentId: string | null) => {
  createParent.value = parentId ? flat.value.find((o) => o.id === parentId) ?? null : null
  createForm.code = ''
  createForm.name = ''
  createForm.sort = 0
  createVisible.value = true
}

const submitCreate = async () => {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await orgsApi.createOrg({
      parentId: createParent.value?.id ?? null,
      code: createForm.code,
      name: createForm.name,
      sort: createForm.sort
    })
    createVisible.value = false
    ElMessage.success('已创建')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '创建失败')
  } finally {
    saving.value = false
  }
}

const save = async () => {
  if (!selected.value) return
  saving.value = true
  try {
    const body: UpsertOrgBody = { name: form.name, sort: form.sort, enabled: form.enabled }
    await orgsApi.updateOrg(selected.value.id, body)
    ElMessage.success('已保存')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const openMove = () => {
  moveTarget.value = null
  moveVisible.value = true
}

const submitMove = async () => {
  if (!selected.value) return
  saving.value = true
  try {
    await orgsApi.moveOrg(selected.value.id, moveTarget.value)
    moveVisible.value = false
    ElMessage.success('已移动')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '移动失败')
  } finally {
    saving.value = false
  }
}

const remove = async () => {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(
      `删除部门「${selected.value.name}」？有下级或有用户时会被拒绝（改走「停用」即可）。`,
      '确认删除',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await orgsApi.deleteOrg(selected.value.id)
    selected.value = null
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    // ★ 后端的 400 带可读 hint（"请先处理下级部门……或停用它"）—— 必须展示，否则用户只看到"删除失败"
    hint.value = error instanceof ApiError ? error.message : '删除失败'
    ElMessage.error(hint.value)
  }
}

onMounted(load)
</script>

<style scoped>
.org-split {
  display: grid;
  /* ★ 比例 + 下限，不用 360px 定值（`#42`）：组织树要在「层级深」时才需要宽度，
     固定 360px 在只有三层时是浪费、在七八层时又不够。280px 是下限（再窄缩进就没了）。 */
  grid-template-columns: minmax(280px, 2fr) minmax(0, 5fr);
  gap: var(--space-md);
  align-items: start;
}

/* 窄屏换形态：树与详情并排会让树只剩两三层可见。 */
@media (max-width: 1280px) {
  .org-split {
    grid-template-columns: minmax(0, 1fr);
  }
}
.tree-panel,
.detail-panel {
  padding: var(--space-md);
}
.panel-hd {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-sm);
}
.panel-hd .h4 {
  margin-right: auto;
}
.node {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
}
.node.off {
  opacity: 0.55;
}
.node-name {
  font-weight: 500;
}
.detail-form {
  margin-top: var(--space-sm);
}
.notice {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
@media (max-width: 900px) {
  .org-split {
    grid-template-columns: 1fr;
  }
}
</style>
