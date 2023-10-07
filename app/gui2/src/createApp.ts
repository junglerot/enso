import { createPinia } from 'pinia'
import { createApp } from 'vue'
import AppRoot from './App.vue'
import './assets/main.css'
import type { StringConfig } from './main'

export function mountProjectApp(rootProps: {
  config: StringConfig | null
  accessToken: string | null
  metadata?: object | undefined
}) {
  const app = createApp(AppRoot, rootProps)
  app.use(createPinia())
  app.mount('#app')
  return app
}
