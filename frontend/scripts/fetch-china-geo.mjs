/**
 * 下载中国地图 GeoJSON（省级边界）到 frontend/public/geo/china.json
 *
 * 用法：
 *   cd frontend
 *   node scripts/fetch-china-geo.mjs
 * 或
 *   pnpm run fetch:geo
 *
 * ---------------------------------------------------------------------------
 * 为什么做成脚本而不是直接把文件提交进仓库？
 *
 *   1. **体积**：省级边界约 580KB，没必要让每次 clone 都背上它；
 *   2. **许可**：该数据来自阿里云 DataV.GeoAtlas（数据源为高德），
 *      适合学习与内部演示，**商用前请自行确认授权**，或改用有明确商用许可的数据
 *      （如自然资源部标准地图服务、天地图、或自购的商业地理数据）。
 *      把第三方数据放在仓库里再分发，比自己跑一次下载脚本风险更大。
 *   3. **可追溯**：脚本里写清楚来源与时间，比一个来源不明的 json 文件更负责。
 *
 * 下载产物 `public/geo/china.json` 已在 .gitignore 里忽略。
 * 如果没跑这个脚本，前端不会崩：底图会退化为程序化生成的模拟地图，
 * 并在 HUD 上提示"中国地图数据未加载"。
 * ---------------------------------------------------------------------------
 */

import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const OUTPUT = resolve(HERE, '../public/geo/china.json')

/**
 * 按优先级尝试的数据源。
 * 第一个是阿里云 DataV.GeoAtlas 的中国全量（含省级边界 + 中心点 + adcode），
 * 后面的 ECharts 历史地图包作为备用（结构略旧，但字段足够画图）。
 */
const SOURCES = [
  'https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json',
  'https://cdn.jsdelivr.net/npm/echarts@4.9.0/map/json/china.json',
]

function download(url) {
  return new Promise((resolvePromise, rejectPromise) => {
    // 用动态 import 拿 https，避免在纯 ESM 里 require
    import('node:https')
      .then(({ default: https }) => {
        const request = https.get(url, { timeout: 30_000 }, (response) => {
          if (response.statusCode !== 200) {
            response.resume()
            rejectPromise(new Error(`HTTP ${response.statusCode}`))
            return
          }
          const chunks = []
          response.on('data', (chunk) => chunks.push(chunk))
          response.on('end', () => resolvePromise(Buffer.concat(chunks)))
        })
        request.on('timeout', () => {
          request.destroy(new Error('请求超时'))
        })
        request.on('error', rejectPromise)
      })
      .catch(rejectPromise)
  })
}

async function main() {
  for (const url of SOURCES) {
    try {
      process.stdout.write(`尝试下载：${url}\n`)
      const buffer = await download(url)
      const text = buffer.toString('utf8')
      // 先解析一次，确认是合法 GeoJSON 再落盘 —— 别把 404 页面存成地图数据
      const geo = JSON.parse(text)
      if (!geo || geo.type !== 'FeatureCollection' || !Array.isArray(geo.features)) {
        throw new Error('返回内容不是 FeatureCollection')
      }

      await mkdir(dirname(OUTPUT), { recursive: true })
      await writeFile(OUTPUT, text, 'utf8')

      const names = geo.features
        .map((f) => f.properties?.name)
        .filter(Boolean)
        .slice(0, 6)
      process.stdout.write(
        `✅ 已写入 ${OUTPUT}\n` +
          `   省级要素：${geo.features.length} 个（${names.join('、')} …）\n` +
          `   文件大小：${(buffer.length / 1024).toFixed(0)} KB\n` +
          `   数据来源：${url}\n`,
      )
      return
    } catch (error) {
      process.stdout.write(`   ✗ 失败：${error.message}\n`)
    }
  }
  process.exitCode = 1
  process.stdout.write('❌ 所有数据源均不可用。前端仍可运行，但底图会退化为程序化模拟地图。\n')
}

main()
