#!/usr/bin/env node
/*
 * 「代码查询 · 深度分析」的**无后端真机截图**。
 *
 * 为什么要这一套：这一页最要紧的几件事（四态的颜色、卡片变灰、缺口单独一行、
 * 人工核实清单全是问句、大模型未启用时其余几块还在）**只有画出来才看得出来**。
 * 而其中任何一条都不是单元测试能覆盖的——测试断言的是数据，这里断言的是「读起来对不对」。
 *
 * 做法：起一个假后端（只回夹具 JSON），把已经构建好的 `frontend/dist` 静态发出去，
 * 再用 Edge 的无头模式打开，通过 CDP 真的去点那两个按钮，然后整页截图。
 *
 * **不需要真后端，也不需要数据库。** 夹具由 `CodeLookupAnalysisFixtureTest` 在
 * 每次 `mvn test` 时从真实代码里导出，所以它不会与 DTO 漂移。
 *
 * 用法：
 *   node automation/scripts/verify_analysis_page.mjs                      # 全跑
 *   node automation/scripts/verify_analysis_page.mjs --case stock         # 只跑个股
 *   node automation/scripts/verify_analysis_page.mjs --fail-analysis      # 只跑失败域那张图（另存为 *-failed）
 *
 * 前置：`cd frontend && npx vite build`，以及 `cd backend && mvn -o test -Dtest=CodeLookupAnalysisFixtureTest`
 */

import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { readFile, writeFile, mkdir, rm, readdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'

// ---------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------

const args = process.argv.slice(2)
const flag = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback
}
const has = (name) => args.includes(`--${name}`)

const REPO = resolve(import.meta.dirname, '..', '..')
const FIXTURE = resolve(REPO, flag('fixture', 'backend/target/analysis-fixture.json'))
const DIST = resolve(REPO, flag('dist', 'frontend/dist'))
const OUT = resolve(REPO, flag('out', 'automation/screenshots'))
const PORT = Number(flag('port', '8091'))
const ONLY = flag('case', null)
const FAIL_ANALYSIS = has('fail-analysis')

const EDGE_CANDIDATES = [
  process.env.EDGE_PATH,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/microsoft-edge',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
].filter(Boolean)

// ---------------------------------------------------------------------
// 假后端
// ---------------------------------------------------------------------

const fixture = JSON.parse(await readFile(FIXTURE, 'utf8'))
const SCENARIOS = { '000300': 'index', '930740': 'offpool' }
const scenarioOf = (code) => SCENARIOS[code] ?? 'stock'

/** 页面要的那几个形状。每个 `data` 外面都套 `{code,message,data}`——与真后端一致。 */
function apiResult(status, message, data) {
  return { code: status, message, data }
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
}

const SEED = `<!doctype html><meta charset="utf-8"><title>seed</title><script>
localStorage.setItem('briefmind_token', 'fake-token-for-screenshots');
localStorage.setItem('briefmind_user', JSON.stringify({
  id: 1, email: 'demo@example.com', displayName: '演示账号',
  role: 'USER', accountType: 'DEMO'
}));
location.replace('/code-lookup');
</script>`

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const path = url.pathname

  if (path === '/__seed') {
    res.writeHead(200, { 'Content-Type': MIME['.html'] })
    res.end(SEED)
    return
  }

  if (path.startsWith('/api/')) {
    const code = url.searchParams.get('code') || ''
    const json = (status, body) => {
      res.writeHead(status, { 'Content-Type': MIME['.json'] })
      res.end(JSON.stringify(body))
    }

    if (path === '/api/auth/me') {
      return json(200, apiResult(200, 'ok', {
        id: 1, email: 'demo@example.com', displayName: '演示账号',
        role: 'USER', accountType: 'DEMO',
      }))
    }
    if (path === '/api/code-lookup') {
      return json(200, apiResult(200, 'ok', fixture[scenarioOf(code)].lookup))
    }
    if (path === '/api/code-lookup/analysis') {
      if (FAIL_ANALYSIS) {
        // 失败域那张图用的就是这一支：分析段整段失败，主查询照常。
        return json(200, apiResult(503, '行情源都没问到 300274 的报价', null))
      }
      return json(200, apiResult(200, 'ok', fixture[scenarioOf(code)].analysis))
    }
    if (path === '/api/code-lookup/analysis/brief') {
      return json(200, apiResult(200, 'ok', fixture[scenarioOf(code)].brief))
    }

    /*
     * **没打桩的接口回 500，不回空数据。** 这一步是这一整段的关键：
     * 一个「回 {} 的兜底」会让页面上的格子静静地显示成「—」，
     * 看起来像「这次没取到数」，没人会去查假接口是不是少写了一条。
     * 500 会在页面上直接炸出一块红字，写清是哪个路径没打桩。
     */
    return json(500, {
      code: 500,
      message: `假接口没有为 ${path} 打桩——补上它，不要让页面把「没打桩」显示成「没取到数」`,
      data: null,
    })
  }

  // 静态资源 + SPA 回退
  const rel = path === '/' ? '/index.html' : path
  const file = join(DIST, rel)
  if (existsSync(file) && extname(file)) {
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream' })
    res.end(await readFile(file))
    return
  }
  res.writeHead(200, { 'Content-Type': MIME['.html'] })
  res.end(await readFile(join(DIST, 'index.html')))
})

// ---------------------------------------------------------------------
// CDP：一个极小的客户端，够用就行
// ---------------------------------------------------------------------

class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    ws.addEventListener('message', (event) => {
      const msg = JSON.parse(event.data)
      const waiting = this.pending.get(msg.id)
      if (!waiting) return
      this.pending.delete(msg.id)
      msg.error ? waiting.reject(new Error(JSON.stringify(msg.error))) : waiting.resolve(msg.result)
    })
  }

  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }

  async eval(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true,
    })
    if (result.exceptionDetails) {
      throw new Error(`页面里抛错：${result.exceptionDetails.exception?.description
        ?? result.exceptionDetails.text}`)
    }
    return result.result.value
  }

  /** 轮询等一个条件为真。超时就抛，**不静默继续**——静默继续会拍出一张空图。 */
  async waitFor(expression, label, timeoutMs = 20000) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      if (await this.eval(expression)) return
      if (Date.now() > deadline) {
        // 超时的时候把页面上的字抓一段出来。没有这一段，失败信息只有一句
        // 「等某某超时」，而真正的原因（多半是页面上挂着一块红字）就在眼前却看不到。
        const text = await this.eval('document.body.innerText.slice(0, 1200)')
          .catch(() => '(页面读不出来了)')
        throw new Error(`等「${label}」超时（${timeoutMs}ms）。\n`
          + `表达式：${expression}\n页面上的字：\n${text}`)
      }
      await new Promise((r) => setTimeout(r, 150))
    }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function launchEdge(port) {
  const exe = EDGE_CANDIDATES.find((p) => existsSync(p))
  if (!exe) {
    throw new Error(`找不到 Edge。用 EDGE_PATH=<路径> 指一个，试过：${EDGE_CANDIDATES.join(', ')}`)
  }
  const profile = join(tmpdir(), `codelookup-shots-${Date.now()}`)
  const child = spawn(exe, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
    // 新版 Chrome 对来自非浏览器客户端的 CDP 连接要求开这个，否则 WebSocket 直接 403
    '--remote-allow-origins=*',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    '--hide-scrollbars',
    'about:blank',
  ], { stdio: 'ignore' })

  for (let i = 0; i < 100; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/version`)
      if (res.ok) return { child, profile }
    } catch { /* 还没起来 */ }
    await sleep(150)
  }
  throw new Error('Edge 没有在 15 秒内把调试端口开起来')
}

async function openTarget(port) {
  // 新版 Chrome 要求 PUT，GET 会回 405
  const res = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })
  if (!res.ok) throw new Error(`开新标签失败：HTTP ${res.status}`)
  const target = await res.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', () => reject(new Error('连不上标签的调试 WebSocket')), { once: true })
  })
  return { cdp: new Cdp(ws), targetId: target.id }
}

/**
 * 整页截图 + 逐屏切片。
 *
 * 两样都要：整页那张是存档，一眼能看出这一页有多长、各块的比例对不对。
 * 切片是因为这一页展开后有近 6000 像素高——**整页那张缩到能放进视野时，
 * 字已经小到读不出「这一格是空着还是写着原因」**，而那恰恰是这一页最要看的东西。
 * 切片按视口高度滚动着拍，每一张都能当原图读。
 */
async function shoot(cdp, name, maxSlices = 8) {
  const full = `${name}.png`

  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1440, height: 3400, deviceScaleFactor: 1, mobile: false,
  })
  await sleep(300)
  const whole = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  await writeFile(join(OUT, full), Buffer.from(whole.data, 'base64'))

  const VIEWPORT = 1100
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 1440, height: VIEWPORT, deviceScaleFactor: 1, mobile: false,
  })
  await sleep(300)
  const total = await cdp.eval('document.documentElement.scrollHeight')
  const count = Math.min(maxSlices, Math.max(1, Math.ceil(total / VIEWPORT)))

  const slices = []
  for (let i = 0; i < count; i++) {
    const top = Math.min(i * (VIEWPORT - 80), Math.max(0, total - VIEWPORT))
    await cdp.eval(`window.scrollTo(0, ${top})`)
    await sleep(250)
    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
    const file = join(OUT, `${name}-${i + 1}.png`)
    await writeFile(file, Buffer.from(shot.data, 'base64'))
    slices.push(file)
  }
  return [join(OUT, full), ...slices]
}

/** 在受控的 React 输入框里填值：必须走原生 setter，否则 React 收不到这次变更。 */
const SET_INPUT = (selector, value) => `(() => {
  const el = document.querySelector(${JSON.stringify(selector)});
  if (!el) return false;
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(el, ${JSON.stringify(value)});
  el.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
})()`

const CLICK_BY_TEXT = (text) => `(() => {
  const button = [...document.querySelectorAll('button')]
    .find(b => b.textContent.includes(${JSON.stringify(text)}) && !b.disabled);
  if (!button) return false;
  button.click();
  return true;
})()`

// ---------------------------------------------------------------------
// 跑
// ---------------------------------------------------------------------

async function runScenario(port, scenario, code, label) {
  const { cdp, targetId } = await openTarget(port)
  try {
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')

    await cdp.send('Page.navigate', { url: `http://127.0.0.1:${PORT}/__seed` })
    await cdp.waitFor('!!document.querySelector(".stockpick-panel-row input")', '查询框')

    if (!await cdp.eval(SET_INPUT('.stockpick-panel-row input', code))) {
      throw new Error('没找到代码输入框')
    }
    if (!await cdp.eval(CLICK_BY_TEXT('查询'))) throw new Error('没找到「查询」按钮')
    await cdp.waitFor('!!document.querySelector(".codelookup-meta")', '主查询结果')

    if (!FAIL_ANALYSIS) {
      if (!await cdp.eval(CLICK_BY_TEXT('开始深度分析'))) throw new Error('没找到「开始深度分析」按钮')
      await cdp.waitFor('!!document.querySelector(".codelookup-tendency")', '倾向标签')

      if (!await cdp.eval(CLICK_BY_TEXT('生成深度解读'))) throw new Error('没找到「生成深度解读」按钮')
      await cdp.waitFor(
        '!!document.querySelector(".codelookup-ai .market-markdown") '
        + '|| !!document.querySelector(".codelookup-ai-failure")', '大模型解读')
    } else {
      if (!await cdp.eval(CLICK_BY_TEXT('开始深度分析'))) throw new Error('没找到「开始深度分析」按钮')
      await cdp.waitFor('!!document.querySelector(".stockpick-error")', '分析失败提示')
    }

    const files = await shoot(cdp, label)
    console.log(`  ✓ ${label}（整页 + ${files.length - 1} 屏切片）`)
    return files
  } finally {
    await fetch(`http://127.0.0.1:${port}/json/close/${targetId}`).catch(() => {})
  }
}

async function main() {
  if (!existsSync(join(DIST, 'index.html'))) {
    throw new Error(`找不到 ${DIST}/index.html。先跑：cd frontend && npx vite build`)
  }
  if (!existsSync(FIXTURE)) {
    throw new Error(`找不到夹具 ${FIXTURE}。先跑：cd backend && mvn -o test -Dtest=CodeLookupAnalysisFixtureTest`)
  }
  await mkdir(OUT, { recursive: true })

  await new Promise((r) => server.listen(PORT, '127.0.0.1', r))
  const { child, profile } = await launchEdge(PORT + 1)

  try {
    /*
     * 失败域那一次**另存一个名字**：它和正常那一次的文件名要是撞上，
     * 后跑的会把先跑的盖掉，而两张图放在一起比才看得出「分析段挂了，
     * 主查询与数字明细照常」——被盖掉的那张往往是更该留的那张。
     */
    const name = (base) => (FAIL_ANALYSIS ? `${base}-failed` : base)
    // `--fail-analysis` 不给 `--case` 时只跑个股那一次：失败域这一张图上
    // 分析段是空的，指数那份没有什么新东西可看，多跑只是多 6 张一样的图。
    const only = FAIL_ANALYSIS && !ONLY ? 'stock' : ONLY
    const shots = []
    if (!only || only === 'stock') {
      shots.push(...await runScenario(PORT + 1, 'stock', '300274', name('analysis-stock')))
    }
    if (!only || only === 'index') {
      shots.push(...await runScenario(PORT + 1, 'index', '000300', name('analysis-index')))
    }
    // 池外指数：不取行情、体检整体不适用、参判维度只有 2 个 → 必然「数据不足」。
    // 这一张还捎带覆盖大模型未启用的降级块（夹具里那一次调用返回的就是失败文案）。
    if (!only || only === 'offpool') {
      shots.push(...await runScenario(PORT + 1, 'offpool', '930740', name('analysis-offpool')))
    }
    console.log('\n截图：')
    for (const s of shots) console.log(`  ${s}`)
  } finally {
    child.kill()
    server.close()
    await rm(profile, { recursive: true, force: true }).catch(() => {})
  }
}

try {
  await main()
  process.exit(0)
} catch (error) {
  console.error(`\n失败：${error.message}`)
  process.exit(1)
}
