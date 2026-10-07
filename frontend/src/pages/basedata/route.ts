import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'basedata',
    name: 'basedata',
    component: () => import('./Index.vue'),
    // ★ 入口就卡写权限（platform:dict:manage）：字典是基础数据，改一次影响全平台的下拉与既有数据的展示。
    //   读接口登录即可（上传/建档的下拉要读它），但那不构成"能进维护页"的理由。
    meta: { title: '基础数据', permission: PERM.dictManage }
  }
] satisfies RouteRecordRaw[]
