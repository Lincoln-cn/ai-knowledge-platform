import type { App } from 'vue'
import type { Router, NavigationGuard } from 'vue-router'
import { createRouter, createWebHashHistory } from 'vue-router'
import { routes } from '@/router/routes'

export function createAppRouter(): Router {
  return createRouter({
    history: createWebHashHistory(),
    routes,
    scrollBehavior: () => ({ top: 0 })
  })
}

export function installRouter(app: App, guards: NavigationGuard[] = []) {
  const router = createAppRouter()
  guards.forEach((guard) => router.beforeEach(guard))
  app.use(router)
}
