import dayjs from 'dayjs'
import type {
  ConflictSeverity,
  ConflictType,
  DroneStatus,
  EventSeverity,
  OrderStatus,
  Priority,
  TaskStatus,
  ZoneType,
} from '@/api/types'

/**
 * 展示层格式化与枚举字典。
 *
 * 把"枚举 → 中文文案 / 颜色"的映射集中在这里，好处是：
 * 后端加一个枚举值，前端只改这一个文件，不会出现"某个页面漏了翻译显示成英文"的情况。
 */

/**
 * Ant Design 标签支持的语义色。
 *
 * 这里的取值必须和 Ant Design Vue 的 `TagColor` 完全对齐：
 *  - 状态色：default / processing / success / warning / error
 *  - 预设色：pink / red / yellow / orange / cyan / green / blue / purple / geekblue / magenta / volcano / gold / lime
 * 少写一个（比如漏了 'gold'）不会在运行时报错，但会让 `vue-tsc` 在这一行失败，
 * 而且报错位置在字典定义处、离真正用它的模板很远，排查起来很费劲 —— 所以一次性写全常用的。
 */
export type TagColor =
  | 'default'
  | 'processing'
  | 'success'
  | 'warning'
  | 'error'
  | 'blue'
  | 'purple'
  | 'cyan'
  | 'magenta'
  | 'orange'
  | 'green'
  | 'red'
  | 'gold'
  | 'geekblue'
  | 'volcano'
  | 'lime'
  | 'pink'
  | 'yellow'

// ---------------------------------------------------------------------------
// 时间与数值
// ---------------------------------------------------------------------------

export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss')
}

export function formatTime(value?: string | null): string {
  if (!value) return '—'
  return dayjs(value).format('HH:mm:ss')
}

/** 相对时间，例如"3 分钟前"。用于事件流这种"关心新旧"的列表。 */
export function formatFromNow(value?: string | null): string {
  if (!value) return '—'
  const diffSeconds = dayjs().diff(dayjs(value), 'second')
  if (diffSeconds < 5) return '刚刚'
  if (diffSeconds < 60) return `${diffSeconds} 秒前`
  if (diffSeconds < 3600) return `${Math.floor(diffSeconds / 60)} 分钟前`
  if (diffSeconds < 86400) return `${Math.floor(diffSeconds / 3600)} 小时前`
  return `${Math.floor(diffSeconds / 86400)} 天前`
}

/** 距离：不足 1km 显示米，否则显示两位小数的公里 */
export function formatDistance(meters?: number | null): string {
  if (meters === undefined || meters === null) return '—'
  if (meters < 1000) return `${Math.round(meters)} m`
  return `${(meters / 1000).toFixed(2)} km`
}

/** 时长：秒 → 人类可读 */
export function formatDuration(seconds?: number | null): string {
  if (seconds === undefined || seconds === null) return '—'
  if (seconds < 60) return `${Math.round(seconds)} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分 ${Math.round(seconds % 60)} 秒`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
}

export function formatPercent(value?: number | null, digits = 1): string {
  if (value === undefined || value === null) return '—'
  return `${Number(value).toFixed(digits)}%`
}

/** 比率（0~1）→ 百分比字符串 */
export function formatRate(value?: number | null, digits = 1): string {
  if (value === undefined || value === null) return '—'
  return `${(Number(value) * 100).toFixed(digits)}%`
}

export function formatEnergy(wh?: number | null): string {
  if (wh === undefined || wh === null) return '—'
  return `${Number(wh).toFixed(1)} Wh`
}

// ---------------------------------------------------------------------------
// 枚举字典
// ---------------------------------------------------------------------------

export const ORDER_STATUS_TEXT: Record<OrderStatus, string> = {
  CREATED: '待派单',
  PLANNING: '规划中',
  QUEUED: '排队中',
  ASSIGNED: '已派单',
  IN_FLIGHT: '配送中',
  DELIVERED: '已送达',
  FAILED: '失败',
  CANCELLED: '已取消',
}

export const ORDER_STATUS_COLOR: Record<OrderStatus, TagColor> = {
  CREATED: 'default',
  PLANNING: 'processing',
  QUEUED: 'cyan',
  ASSIGNED: 'blue',
  IN_FLIGHT: 'processing',
  DELIVERED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
}

export const TASK_STATUS_TEXT: Record<TaskStatus, string> = {
  PENDING: '待分配',
  ASSIGNED: '已分配',
  TAKEOFF: '起飞中',
  CRUISING: '巡航中',
  DELIVERING: '投递中',
  RETURNING: '返航中',
  CHARGING: '补能中',
  COMPLETED: '已完成',
  ABORTED: '已中止',
  FAILED: '失败',
}

export const TASK_STATUS_COLOR: Record<TaskStatus, TagColor> = {
  PENDING: 'default',
  ASSIGNED: 'blue',
  TAKEOFF: 'cyan',
  CRUISING: 'processing',
  DELIVERING: 'purple',
  RETURNING: 'orange',
  CHARGING: 'gold',
  COMPLETED: 'success',
  ABORTED: 'default',
  FAILED: 'error',
}

export const DRONE_STATUS_TEXT: Record<DroneStatus, string> = {
  IDLE: '空闲待命',
  ASSIGNED: '已接单',
  IN_FLIGHT: '飞行中',
  RETURNING: '返航中',
  CHARGING: '充电中',
  MAINTENANCE: '维护中',
  OFFLINE: '离线',
}

export const DRONE_STATUS_COLOR: Record<DroneStatus, TagColor> = {
  IDLE: 'success',
  ASSIGNED: 'blue',
  IN_FLIGHT: 'processing',
  RETURNING: 'orange',
  CHARGING: 'cyan',
  MAINTENANCE: 'warning',
  OFFLINE: 'default',
}

/** 地图上无人机圆点的颜色（与状态标签保持一致的语义） */
export const DRONE_STATUS_HEX: Record<DroneStatus, string> = {
  IDLE: '#52c41a',
  ASSIGNED: '#1677ff',
  IN_FLIGHT: '#1677ff',
  RETURNING: '#fa8c16',
  CHARGING: '#13c2c2',
  MAINTENANCE: '#faad14',
  OFFLINE: '#8c8c8c',
}

export const PRIORITY_TEXT: Record<Priority, string> = {
  LOW: '低',
  NORMAL: '普通',
  HIGH: '高',
  URGENT: '紧急',
}

export const PRIORITY_COLOR: Record<Priority, TagColor> = {
  LOW: 'default',
  NORMAL: 'blue',
  HIGH: 'orange',
  URGENT: 'red',
}

export const ZONE_TYPE_TEXT: Record<ZoneType, string> = {
  NO_FLY: '禁飞区',
  RESTRICTED: '限飞区',
  CORRIDOR: '航路走廊',
  WARNING: '警示区',
  BUILDING: '建筑障碍',
}

export const ZONE_TYPE_COLOR: Record<ZoneType, TagColor> = {
  NO_FLY: 'red',
  RESTRICTED: 'orange',
  CORRIDOR: 'green',
  WARNING: 'gold',
  BUILDING: 'purple',
}

export const CONFLICT_TYPE_TEXT: Record<ConflictType, string> = {
  DRONE_DRONE: '机-机冲突',
  DRONE_ZONE: '机-区冲突',
  DEADLINE_RISK: '超时风险',
  BATTERY_RISK: '电量风险',
}

export const CONFLICT_SEVERITY_TEXT: Record<ConflictSeverity, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '严重',
}

export const CONFLICT_SEVERITY_COLOR: Record<ConflictSeverity, TagColor> = {
  LOW: 'default',
  MEDIUM: 'gold',
  HIGH: 'orange',
  CRITICAL: 'red',
}

export const EVENT_SEVERITY_COLOR: Record<EventSeverity, TagColor> = {
  DEBUG: 'default',
  INFO: 'blue',
  WARN: 'orange',
  ERROR: 'red',
}

/** 事件类型 → 中文（后端 event_log.event_type 的取值） */
export const EVENT_TYPE_TEXT: Record<string, string> = {
  ORDER_CREATED: '订单创建',
  ORDER_STATUS_CHANGED: '订单状态变化',
  TASK_ASSIGNED: '任务派发',
  TASK_STATUS_CHANGED: '任务状态变化',
  TASK_COMPLETED: '任务完成',
  TASK_FAILED: '任务失败',
  DRONE_BATTERY_LOW: '电量告警',
  CONFLICT_DETECTED: '冲突发现',
  SIMULATION_STARTED: '仿真启动',
  SIMULATION_STOPPED: '仿真停止',
  DRONE_RELAY_DIVERTED: '自主改航补能',
  DRONE_RELAY_ARRIVED: '抵达充电站',
  DRONE_RELAY_RESUMED: '补能完成续飞',
}

export function eventTypeText(type: string): string {
  return EVENT_TYPE_TEXT[type] ?? type
}

/** 仿真运行状态 → 中文 */
export const RUN_STATUS_TEXT: Record<string, string> = {
  CREATED: '已创建',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  STOPPED: '已停止',
  FINISHED: '已结束',
}

/** 派单策略 → 中文说明（与后端 DispatchStrategy.description() 的含义保持一致） */
export const STRATEGY_TEXT: Record<string, string> = {
  NEAREST_AVAILABLE: '最近可用（响应最快）',
  BATTERY_FIRST: '电量优先（续航裕度最大）',
  LOAD_BALANCE: '负载均衡（机队使用最均匀）',
}
