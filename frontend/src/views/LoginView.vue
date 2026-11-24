<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRightOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  LockOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { useAuth } from '@/store/auth'

/**
 * 登录页：做成“空域监控台 + 安全接入终端”的双栏场景。
 *
 * 左侧不是装饰图，而是一条与业务同构的仿真叙事：雷达扫描、计划航路、
 * 无人机沿航路移动、空域扇区与机队读数。右侧是操作员接入面板，
 * 使用编号步骤、等宽字体和终端式输入框，避免“居中一张卡片”的通用登录页。
 */

const router = useRouter()
const route = useRoute()
const auth = useAuth()

const loading = ref(false)
const granted = ref(false)
const showPassword = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

/** 装饰用：终端时钟与会话号，让页面有“正在运行”的读数感 */
const clock = ref('')
const sessionId = ref(Math.random().toString(16).slice(2, 8).toUpperCase())
let clockTimer: number | null = null

function tickClock(): void {
  const now = new Date()
  const date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  const time = now.toLocaleTimeString('zh-CN', { hour12: false })
  clock.value = `${date}  ${time}`
}

onMounted(() => {
  tickClock()
  clockTimer = window.setInterval(tickClock, 1000)
})

onUnmounted(() => {
  if (clockTimer !== null) window.clearInterval(clockTimer)
})

async function handleSubmit(): Promise<void> {
  if (granted.value) return
  if (!form.username.trim() || !form.password) {
    error.value = '请输入操作员账号和访问密钥'
    return
  }
  error.value = ''
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    granted.value = true
    // 留一小段时间展示 ACCESS GRANTED，再进入看板
    await new Promise((resolve) => setTimeout(resolve, 420))
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (e) {
    error.value = e instanceof Error && e.message ? e.message : '身份验证失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <!-- ======================= 左：空域监控台（装饰） ======================= -->
    <aside class="login-visual" aria-hidden="true">
      <span class="visual-grid" />
      <span class="visual-scan" />

      <div class="visual-top">
        <span class="visual-kicker">LIVE AIRSPACE <b>/</b> SIMULATION</span>
        <span class="visual-clock">{{ clock }}</span>
      </div>

      <div class="radar">
        <span class="radar-ring radar-ring--1" />
        <span class="radar-ring radar-ring--2" />
        <span class="radar-ring radar-ring--3" />
        <span class="radar-ring radar-ring--4" />
        <span class="radar-cross radar-cross--x" />
        <span class="radar-cross radar-cross--y" />
        <span class="radar-sweep" />

        <span class="radar-blip radar-blip--a" />
        <span class="radar-blip radar-blip--b" />
        <span class="radar-blip radar-blip--c" />
        <span class="radar-blip radar-blip--d" />

        <svg class="radar-paths" viewBox="0 0 600 600" fill="none">
          <path
            d="M70 500 C 170 440, 210 300, 330 260 S 500 170, 552 88"
            stroke="rgba(56,232,255,.30)"
            stroke-width="1.2"
            stroke-dasharray="5 8"
          />
          <path
            d="M120 110 C 210 160, 270 290, 360 350 S 500 460, 548 522"
            stroke="rgba(56,232,255,.18)"
            stroke-width="1"
            stroke-dasharray="4 10"
          />
          <path
            d="M86 300 C 190 300, 250 190, 370 180 S 510 210, 552 250"
            stroke="rgba(47,214,165,.20)"
            stroke-width="1"
            stroke-dasharray="3 9"
          />

          <!-- 无人机沿计划航路移动（SMIL，无 JS 开销） -->
          <circle class="drone-motion" r="5" fill="#9ff0ff" opacity=".95">
            <animateMotion
              dur="15s"
              repeatCount="indefinite"
              path="M70 500 C 170 440, 210 300, 330 260 S 500 170, 552 88"
            />
          </circle>
          <circle class="drone-motion" r="3.5" fill="#2fd6a5" opacity=".9">
            <animateMotion
              dur="21s"
              repeatCount="indefinite"
              path="M120 110 C 210 160, 270 290, 360 350 S 500 460, 548 522"
            />
          </circle>
          <circle class="drone-motion" r="3" fill="#38e8ff" opacity=".8">
            <animateMotion
              dur="26s"
              repeatCount="indefinite"
              path="M86 300 C 190 300, 250 190, 370 180 S 510 210, 552 250"
            />
          </circle>
        </svg>

        <span class="radar-label radar-label--a">UAV-017 · 120m</span>
        <span class="radar-label radar-label--b">UAV-204 · 96m</span>
        <span class="radar-label radar-label--c">SECTOR 14</span>

        <div class="radar-core">
          <span class="core-dot" />
          <b>HQ</b>
          <i>31.2304 N</i>
        </div>
      </div>

      <div class="visual-readout">
        <div><span>LAT</span>31.2304° N</div>
        <div><span>LON</span>121.4737° E</div>
        <div><span>SECTORS</span>14</div>
        <div><span>FLEET</span>26</div>
      </div>

      <div class="visual-log">
        <div><b>&gt;</b> uplink <i /> <em>established</em></div>
        <div><b>&gt;</b> airspace <i /> <em>14 sectors</em></div>
        <div><b>&gt;</b> fleet <i /> <em>26 units</em></div>
        <div class="log-cursor"><b>&gt;</b><span class="cursor" /></div>
      </div>

      <div class="visual-foot">SIMULATION ENVIRONMENT / NO LIVE AIRCRAFT</div>
    </aside>

    <!-- ======================= 右：安全接入终端 ======================= -->
    <main class="login-panel">
      <span class="panel-scan" aria-hidden="true" />
      <span class="panel-vertical" aria-hidden="true">AUTHORIZED PERSONNEL ONLY</span>
      <span class="panel-watermark" aria-hidden="true">SD-01</span>

      <div class="login-inner">
        <div class="login-brand">
          <span class="brand-mark">SD</span>
          <span class="brand-copy">
            <b>SKY DISPATCH</b>
            <i>低空配送调度仿真平台</i>
          </span>
        </div>

        <div class="login-kicker">
          SECURE OPERATOR ACCESS
          <span>// 01</span>
        </div>
        <h1 class="login-title">进入调度中心</h1>
        <p class="login-desc">完成身份验证后接入实时空域、订单队列与机队调度。</p>

        <form class="login-form" @submit.prevent="handleSubmit">
          <label class="field">
            <span class="field-index">01</span>
            <span class="field-main">
              <span class="field-label">OPERATOR ID <i>操作员账号</i></span>
              <span class="field-input">
                <UserOutlined class="field-icon" />
                <input
                  v-model="form.username"
                  type="text"
                  name="username"
                  autocomplete="username"
                  placeholder="请输入账号"
                  :disabled="loading || granted"
                  @input="error = ''"
                />
                <span class="field-scan" />
              </span>
            </span>
          </label>

          <label class="field">
            <span class="field-index">02</span>
            <span class="field-main">
              <span class="field-label">ACCESS KEY <i>访问密钥</i></span>
              <span class="field-input">
                <LockOutlined class="field-icon" />
                <input
                  v-model="form.password"
                  :type="showPassword ? 'text' : 'password'"
                  name="password"
                  autocomplete="current-password"
                  placeholder="请输入访问密钥"
                  :disabled="loading || granted"
                  @input="error = ''"
                />
                <button
                  type="button"
                  class="field-eye"
                  :aria-label="showPassword ? '隐藏密钥' : '显示密钥'"
                  @click="showPassword = !showPassword"
                >
                  <EyeInvisibleOutlined v-if="showPassword" />
                  <EyeOutlined v-else />
                </button>
                <span class="field-scan" />
              </span>
            </span>
          </label>

          <div v-if="error" class="login-error" role="alert">
            <span class="error-dot" />
            {{ error }}
          </div>

          <button
            type="submit"
            class="login-submit"
            :class="{ 'is-granted': granted }"
            :disabled="loading || granted"
          >
            <span class="submit-scan" aria-hidden="true" />
            <span v-if="granted" class="submit-content">ACCESS GRANTED / 已授权</span>
            <span v-else-if="loading" class="submit-content">
              <i class="spinner" /> 身份验证中…
            </span>
            <span v-else class="submit-content">
              进入调度中心 <ArrowRightOutlined />
            </span>
          </button>
        </form>

        <div class="panel-status">
          <span><i class="status-dot status-dot--ok" />CORE ONLINE</span>
          <span><i class="status-dot status-dot--cyan" />UPLINK STABLE</span>
          <span><i class="status-dot status-dot--warn" />ENCRYPTED</span>
        </div>

        <div class="panel-foot">
          <span>授权人员专用 · 所有操作将被记录</span>
          <span>SESSION {{ sessionId }}</span>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-shell {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(420px, 0.85fr);
  height: 100vh;
  min-height: 640px;
  overflow: auto;
  background:
    radial-gradient(ellipse at 28% 38%, rgba(34, 211, 238, 0.08), transparent 55%),
    linear-gradient(180deg, #030b14, #061322 55%, #030b14);
  color: #d8e8fb;
}

/* ============================ 左侧空域监控台 ============================ */
.login-visual {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 14px;
  padding: 26px clamp(28px, 4vw, 64px);
  overflow: hidden;
  border-right: 1px solid rgba(56, 232, 255, 0.12);
}

.visual-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(56, 232, 255, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(56, 232, 255, 0.055) 1px, transparent 1px);
  background-size: 40px 40px;
  mask-image: radial-gradient(ellipse at 45% 45%, #000 20%, transparent 78%);
  pointer-events: none;
}

.visual-scan {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent, rgba(56, 232, 255, 0.06), transparent);
  transform: translateY(-100%);
  animation: visual-scan 9s linear infinite;
  pointer-events: none;
}

@keyframes visual-scan {
  to {
    transform: translateY(100%);
  }
}

.visual-top {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10px;
  letter-spacing: 1.4px;
  color: rgba(150, 199, 232, 0.62);
}

.visual-kicker b {
  color: rgba(56, 232, 255, 0.6);
  font-weight: 400;
}

.visual-clock {
  color: rgba(159, 240, 255, 0.7);
  font-variant-numeric: tabular-nums;
}

/* 雷达盘 */
.radar {
  position: relative;
  z-index: 1;
  width: min(42vh, 46vw, 500px);
  aspect-ratio: 1;
  margin: 0 auto;
  border-radius: 50%;
  background:
    radial-gradient(circle, rgba(34, 211, 238, 0.08), transparent 62%),
    rgba(3, 12, 22, 0.55);
  box-shadow:
    inset 0 0 60px rgba(34, 211, 238, 0.05),
    0 0 60px rgba(34, 211, 238, 0.06);
}

.radar-ring {
  position: absolute;
  border: 1px solid rgba(56, 232, 255, 0.13);
  border-radius: 50%;
  pointer-events: none;
}

.radar-ring--1 {
  inset: 0;
  border-color: rgba(56, 232, 255, 0.22);
}

.radar-ring--2 {
  inset: 12%;
}

.radar-ring--3 {
  inset: 25%;
}

.radar-ring--4 {
  inset: 38%;
}

.radar-cross {
  position: absolute;
  background: rgba(56, 232, 255, 0.1);
  pointer-events: none;
}

.radar-cross--x {
  top: 50%;
  left: 4%;
  right: 4%;
  height: 1px;
}

.radar-cross--y {
  top: 4%;
  bottom: 4%;
  left: 50%;
  width: 1px;
}

.radar-sweep {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: conic-gradient(
    from 0deg,
    rgba(56, 232, 255, 0.26),
    rgba(56, 232, 255, 0.06) 18%,
    transparent 34%
  );
  mix-blend-mode: screen;
  animation: radar-spin 6.5s linear infinite;
  pointer-events: none;
}

@keyframes radar-spin {
  to {
    transform: rotate(360deg);
  }
}

.radar-paths {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.radar-blip {
  position: absolute;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9ff0ff;
  box-shadow: 0 0 12px rgba(56, 232, 255, 0.95);
  animation: blip-pulse 2.6s ease-in-out infinite;
  pointer-events: none;
}

.radar-blip--a {
  top: 30%;
  left: 66%;
}

.radar-blip--b {
  top: 62%;
  left: 32%;
  animation-delay: -0.8s;
}

.radar-blip--c {
  top: 44%;
  left: 22%;
  background: #2fd6a5;
  box-shadow: 0 0 12px rgba(47, 214, 165, 0.9);
  animation-delay: -1.6s;
}

.radar-blip--d {
  top: 72%;
  left: 74%;
  background: #f0a93b;
  box-shadow: 0 0 12px rgba(240, 169, 59, 0.9);
  animation-delay: -0.3s;
}

@keyframes blip-pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.7);
  }
  50% {
    opacity: 1;
    transform: scale(1.15);
  }
}

.radar-label {
  position: absolute;
  z-index: 2;
  padding: 2px 6px;
  border-left: 1px solid rgba(56, 232, 255, 0.45);
  background: rgba(3, 12, 22, 0.6);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 9px;
  letter-spacing: 0.6px;
  color: rgba(159, 240, 255, 0.72);
  pointer-events: none;
}

.radar-label--a {
  top: 24%;
  left: 68%;
}

.radar-label--b {
  top: 66%;
  left: 40%;
}

.radar-label--c {
  top: 15%;
  left: 18%;
  color: rgba(150, 199, 232, 0.5);
}

.radar-core {
  position: absolute;
  top: 50%;
  left: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  transform: translate(-50%, -50%);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 9px;
  letter-spacing: 0.8px;
  color: rgba(159, 240, 255, 0.75);
}

.core-dot {
  width: 8px;
  height: 8px;
  margin-bottom: 6px;
  background: #9ff0ff;
  box-shadow: 0 0 16px rgba(56, 232, 255, 0.9);
  transform: rotate(45deg);
}

.radar-core b {
  font-size: 11px;
  letter-spacing: 1.6px;
  color: #eafcff;
}

.radar-core i {
  margin-top: 2px;
  font-style: normal;
  color: rgba(150, 199, 232, 0.55);
}

/* 读数与终端日志 */
.visual-readout {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  width: min(42vh, 46vw, 500px);
  max-width: 100%;
  margin: 0 auto;
  border: 1px solid rgba(56, 232, 255, 0.1);
  background: rgba(56, 232, 255, 0.08);
}

.visual-readout > div {
  padding: 8px 10px;
  background: rgba(3, 12, 22, 0.7);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  letter-spacing: 0.4px;
  color: rgba(216, 232, 251, 0.8);
}

.visual-readout span {
  display: block;
  margin-bottom: 2px;
  font-size: 8px;
  letter-spacing: 1.2px;
  color: rgba(56, 232, 255, 0.45);
}

.visual-log {
  position: relative;
  z-index: 2;
  width: min(42vh, 46vw, 500px);
  max-width: 100%;
  margin: 0 auto;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10px;
  line-height: 1.9;
  color: rgba(150, 199, 232, 0.5);
}

.visual-log b {
  margin-right: 8px;
  color: rgba(56, 232, 255, 0.7);
  font-weight: 400;
}

.visual-log i {
  display: inline-block;
  width: 46%;
  margin: 0 8px;
  border-bottom: 1px dotted rgba(56, 232, 255, 0.18);
}

.visual-log em {
  font-style: normal;
  color: rgba(47, 214, 165, 0.72);
}

.log-cursor {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cursor {
  width: 6px;
  height: 11px;
  background: rgba(56, 232, 255, 0.7);
  animation: cursor-blink 1.1s steps(1) infinite;
}

@keyframes cursor-blink {
  50% {
    opacity: 0;
  }
}

.visual-foot {
  position: relative;
  z-index: 2;
  text-align: center;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 8px;
  letter-spacing: 1.8px;
  color: rgba(150, 199, 232, 0.3);
}

/* ============================ 右侧接入终端 ============================ */
.login-panel {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px clamp(28px, 5vw, 84px);
  overflow: hidden;
  border-left: 1px solid rgba(56, 232, 255, 0.16);
  background: linear-gradient(180deg, rgba(4, 14, 24, 0.65), rgba(4, 11, 20, 0.95));
}

.panel-scan {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent, rgba(56, 232, 255, 0.045), transparent);
  transform: translateY(-100%);
  animation: panel-scan 11s linear infinite;
  pointer-events: none;
}

@keyframes panel-scan {
  to {
    transform: translateY(100%);
  }
}

.panel-vertical {
  position: absolute;
  top: 50%;
  right: 14px;
  transform: translateY(-50%) rotate(90deg);
  transform-origin: center;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 8px;
  letter-spacing: 3px;
  color: rgba(56, 232, 255, 0.18);
  white-space: nowrap;
  pointer-events: none;
}

.panel-watermark {
  position: absolute;
  right: 24px;
  bottom: -18px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 132px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -4px;
  color: transparent;
  -webkit-text-stroke: 1px rgba(56, 232, 255, 0.055);
  pointer-events: none;
  user-select: none;
}

.login-inner {
  position: relative;
  z-index: 2;
  width: 100%;
  max-width: 430px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 52px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid rgba(56, 232, 255, 0.4);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #9ff0ff;
  box-shadow: 0 0 20px rgba(56, 232, 255, 0.22);
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.brand-copy b {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 13px;
  letter-spacing: 2px;
  color: rgba(234, 252, 255, 0.9);
}

.brand-copy i {
  font-style: normal;
  font-size: 10px;
  letter-spacing: 0.8px;
  color: rgba(150, 199, 232, 0.55);
}

.login-kicker {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10px;
  letter-spacing: 2.2px;
  color: rgba(56, 232, 255, 0.55);
}

.login-kicker span {
  margin-left: 8px;
  color: rgba(150, 199, 232, 0.35);
}

.login-title {
  margin: 12px 0 10px;
  font-size: clamp(28px, 3vw, 36px);
  font-weight: 700;
  letter-spacing: 2px;
  color: #eafcff;
  text-shadow: 0 0 28px rgba(56, 232, 255, 0.2);
}

.login-desc {
  margin: 0 0 38px;
  font-size: 12px;
  line-height: 1.8;
  letter-spacing: 0.4px;
  color: rgba(150, 199, 232, 0.58);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.field {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: 12px;
}

.field-index {
  padding-top: 21px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10px;
  letter-spacing: 1px;
  color: rgba(56, 232, 255, 0.38);
}

.field-main {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10px;
  letter-spacing: 1.8px;
  color: rgba(159, 199, 232, 0.6);
}

.field-label i {
  font-style: normal;
  font-size: 10.5px;
  letter-spacing: 0.4px;
  color: rgba(159, 199, 232, 0.3);
}

.field-input {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 44px;
  padding: 0 10px;
  border-bottom: 1px solid rgba(56, 232, 255, 0.2);
  background: linear-gradient(90deg, rgba(56, 232, 255, 0.045), transparent 70%);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.field-input:focus-within {
  border-color: rgba(56, 232, 255, 0.7);
  box-shadow: 0 10px 24px -18px rgba(56, 232, 255, 0.9);
}

.field-icon {
  font-size: 14px;
  color: rgba(56, 232, 255, 0.55);
}

.field-input input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 13px;
  letter-spacing: 0.6px;
  color: #eafcff;
}

.field-input input::placeholder {
  color: rgba(150, 199, 232, 0.28);
}

.field-input input:disabled {
  opacity: 0.6;
}

.field-eye {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 0;
  background: transparent;
  color: rgba(150, 199, 232, 0.45);
  cursor: pointer;
  transition: color 0.2s ease;
}

.field-eye:hover {
  color: rgba(159, 240, 255, 0.9);
}

/* 聚焦时沿输入框扫过的一条光带 */
.field-scan {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, #9ff0ff, transparent);
  opacity: 0;
  transform: scaleX(0.2);
  transition: opacity 0.2s ease, transform 0.2s ease;
  pointer-events: none;
}

.field-input:focus-within .field-scan {
  opacity: 1;
  transform: scaleX(1);
}

.login-error {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 11px;
  border: 1px solid rgba(255, 96, 96, 0.28);
  background: rgba(255, 96, 96, 0.07);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  letter-spacing: 0.3px;
  color: #ffb3b3;
}

.error-dot {
  width: 5px;
  height: 5px;
  flex: 0 0 auto;
  background: #ff6060;
  box-shadow: 0 0 8px rgba(255, 96, 96, 0.9);
  transform: rotate(45deg);
}

.login-submit {
  position: relative;
  display: grid;
  place-items: center;
  width: 100%;
  height: 48px;
  margin-top: 6px;
  overflow: hidden;
  border: 1px solid rgba(56, 232, 255, 0.42);
  clip-path: polygon(10px 0, 100% 0, 100% calc(100% - 10px), calc(100% - 10px) 100%, 0 100%, 0 10px);
  background: linear-gradient(120deg, #1677ff, #22b8e6 45%, #1677ff);
  background-size: 220% 100%;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  letter-spacing: 2px;
  color: #fff;
  cursor: pointer;
  animation: login-flow 6s linear infinite;
  transition: filter 0.2s ease, box-shadow 0.2s ease;
}

@keyframes login-flow {
  from {
    background-position: 0% 50%;
  }
  to {
    background-position: 200% 50%;
  }
}

.login-submit:hover:not(:disabled) {
  filter: brightness(1.12);
  box-shadow: 0 0 28px rgba(56, 232, 255, 0.28);
}

.login-submit:disabled {
  cursor: default;
}

.login-submit.is-granted {
  border-color: rgba(47, 214, 165, 0.6);
  background: linear-gradient(120deg, #0e6b52, #2fd6a5 55%, #0e6b52);
  animation: none;
}

.submit-content {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.submit-scan {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 45%;
  background: linear-gradient(100deg, transparent, rgba(255, 255, 255, 0.28), transparent);
  transform: translateX(-160%);
  pointer-events: none;
}

.login-submit:hover:not(:disabled) .submit-scan {
  animation: submit-scan 1.2s linear infinite;
}

@keyframes submit-scan {
  to {
    transform: translateX(320%);
  }
}

.spinner {
  width: 12px;
  height: 12px;
  border: 1.5px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.panel-status {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-top: 34px;
  padding-top: 16px;
  border-top: 1px solid rgba(56, 232, 255, 0.1);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 9px;
  letter-spacing: 1.2px;
  color: rgba(150, 199, 232, 0.42);
}

.panel-status span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
}

.status-dot--ok {
  background: #2fd6a5;
  box-shadow: 0 0 6px rgba(47, 214, 165, 0.8);
}

.status-dot--cyan {
  background: #38e8ff;
  box-shadow: 0 0 6px rgba(56, 232, 255, 0.8);
}

.status-dot--warn {
  background: #f0a93b;
  box-shadow: 0 0 6px rgba(240, 169, 59, 0.8);
}

.panel-foot {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 22px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 9px;
  letter-spacing: 0.6px;
  color: rgba(150, 199, 232, 0.26);
}

/* ============================ 响应式 ============================ */
@media (max-width: 1080px) {
  .login-shell {
    grid-template-columns: minmax(0, 1fr) minmax(390px, 0.9fr);
  }

  .radar,
  .visual-readout,
  .visual-log {
    width: min(38vh, 42vw, 440px);
    max-width: 100%;
  }
}

@media (max-width: 900px) {
  .login-shell {
    grid-template-columns: 1fr;
    height: auto;
    min-height: 100vh;
  }

  .login-visual {
    display: none;
  }

  .login-panel {
    min-height: 100vh;
    border-left: 0;
  }

  .panel-watermark {
    font-size: 96px;
  }
}

@media (max-width: 480px) {
  .login-panel {
    padding: 32px 22px;
  }

  .login-brand {
    margin-bottom: 36px;
  }

  .login-title {
    font-size: 28px;
  }

  .panel-foot {
    flex-direction: column;
    gap: 4px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .visual-scan,
  .panel-scan,
  .radar-sweep,
  .radar-blip,
  .cursor,
  .login-submit,
  .login-submit .submit-scan,
  .spinner {
    animation: none;
  }

  .drone-motion {
    display: none;
  }
}
</style>
