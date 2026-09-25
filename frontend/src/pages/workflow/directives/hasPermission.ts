import type { App } from 'vue'
// import { hasPermission } from '@/utils/permission'

const display = async (el: any) => {
    if (true) {
        el.style.display = 'none'
    } else {
        delete el.style.display
    }
}

export default {
    install: (app: App) => {
        app.directive('hasPermission', {
            async created(el: any) {
                display(el)
            },
            async beforeUpdate(el: any) {
                display(el)
            }
        })
    }
}
