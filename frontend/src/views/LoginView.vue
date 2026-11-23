<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import { useAuth } from '@/store/auth'

/**
 * 登录页：内部演示平台的入口。
 *
 * 登录成功把 token 写入 localStorage，后续 HTTP 请求与 WebSocket 握手都会带上它。
 * 页面不展示默认账号密码，避免把部署凭据写死在前端。
 */

const router = useRouter()
const route = useRoute()
const auth = useAuth()

const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function handleSubmit(): Promise<void> {
  if (!form.username.trim() || !form.password) {
    message.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    message.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch {
    // 具体错误（账号密码错误 / 网络不可达）已由 API 客户端统一提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <span class="login-grid" aria-hidden="true" />
    <span class="login-scan" aria-hidden="true" />

    <div class="login-card">
      <div class="login-brand">
        <div class="login-logo">SD</div>
        <div>
          <div class="login-title">sky-dispatch</div>
          <div class="login-sub">低空配送调度仿真平台</div>
        </div>
      </div>

      <div class="login-divider" aria-hidden="true" />

      <a-form layout="vertical" class="login-form" @submit.prevent="handleSubmit">
        <a-form-item label="账号">
          <a-input
            v-model:value="form.username"
            size="large"
            placeholder="请输入账号"
            autocomplete="username"
            allow-clear
          >
            <template #prefix><UserOutlined /></template>
          </a-input>
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password
            v-model:value="form.password"
            size="large"
            placeholder="请输入密码"
            autocomplete="current-password"
          >
            <template #prefix><LockOutlined /></template>
          </a-input-password>
        </a-form-item>

        <a-button
          type="primary"
          html-type="submit"
          size="large"
          block
          :loading="loading"
          class="login-submit"
        >
          登录
        </a-button>
      </a-form>

      <div class="login-foot">内部演示环境 · 请使用管理员账号登录</div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 100vh;
  overflow: hidden;
  background:
    radial-gradient(ellipse at 50% 20%, rgba(34, 211, 238, 0.12), transparent 55%),
    linear-gradient(180deg, #050d18, #071426 60%, #050d18);
}

/* 背景网格 + 自检扫描线，和主应用保持同一套 HUD 语言 */
.login-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(56, 232, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 232, 255, 0.06) 1px, transparent 1px);
  background-size: 36px 36px;
  mask-image: radial-gradient(ellipse at center, #000 20%, transparent 75%);
  pointer-events: none;
}

.login-scan {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent, rgba(56, 232, 255, 0.08), transparent);
  transform: translateY(-100%);
  animation: login-scan 7s linear infinite;
  pointer-events: none;
}

@keyframes login-scan {
  to {
    transform: translateY(100%);
  }
}

.login-card {
  position: relative;
  z-index: 1;
  width: 380px;
  max-width: calc(100vw - 40px);
  padding: 30px 30px 22px;
  border: 1px solid rgba(56, 232, 255, 0.22);
  background: linear-gradient(180deg, rgba(12, 30, 48, 0.94), rgba(6, 17, 30, 0.96));
  box-shadow:
    0 0 40px rgba(34, 211, 238, 0.12),
    inset 0 0 30px rgba(34, 211, 238, 0.04);
  clip-path: polygon(14px 0, 100% 0, 100% calc(100% - 14px), calc(100% - 14px) 100%, 0 100%, 0 14px);
}

.login-card::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  --c: rgba(56, 232, 255, 0.55);
  background:
    linear-gradient(var(--c), var(--c)) left top / 14px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) left top / 1px 14px no-repeat,
    linear-gradient(var(--c), var(--c)) right bottom / 14px 1px no-repeat,
    linear-gradient(var(--c), var(--c)) right bottom / 1px 14px no-repeat;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.login-logo {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border: 1px solid rgba(56, 232, 255, 0.4);
  color: #9ff0ff;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 1px;
  box-shadow: 0 0 18px rgba(56, 232, 255, 0.3);
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
}

.login-title {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 1.2px;
  color: #eafcff;
}

.login-sub {
  margin-top: 2px;
  font-size: 11px;
  letter-spacing: 0.6px;
  color: rgba(150, 199, 232, 0.7);
}

.login-divider {
  height: 1px;
  margin: 20px 0 18px;
  background: linear-gradient(90deg, rgba(56, 232, 255, 0.5), transparent);
}

.login-form :deep(.ant-form-item-label > label) {
  color: rgba(159, 199, 232, 0.85);
  font-size: 12px;
  letter-spacing: 0.4px;
}

.login-form :deep(.ant-input-affix-wrapper),
.login-form :deep(.ant-input) {
  background: rgba(4, 14, 24, 0.65);
}

.login-submit {
  margin-top: 6px;
  letter-spacing: 2px;
}

.login-foot {
  margin-top: 16px;
  text-align: center;
  font-size: 11px;
  letter-spacing: 0.3px;
  color: rgba(150, 199, 232, 0.55);
}

@media (prefers-reduced-motion: reduce) {
  .login-scan {
    animation: none;
  }
}
</style>
