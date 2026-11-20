#!/usr/bin/env node
/*
 * 「简报详情页翻页 + 返回原页码」的无后端真机验证。
 *
 * 要验证的两件事都是交互性的，单元测试断言不了：
 *   1. 详情页底部有 1..46 的页码、上一页/下一页，以及输入数字跳转；
 *   2. 从历史简报第 3 页打开一篇，点「返回」要回到第 3 页，而不是第 1 页。
 *
 * 做法与 `verify_analysis_page.mjs` 相同：假后端只回夹具数据，静态发 `frontend/dist`，
 * 用 Edge 无头模式真的去点。**不需要真后端和数据库。**
 *
 * 用法：
 *   node automation/scripts/verify_report_pager.mjs
 *
 * 前置：`cd frontend && npm run build`
 */

import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { readFile, writeFile, mkdir, rm } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { extname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'

const REPO = resolve(import.meta.dirname, '..', '..')
const DIST = resolve(REPO, 'frontend/dist')
const OUT = resolve(REPO, 'automation/screenshots/report-pager')
const PORT = 8095
const CDP_PORT = 8096

const EDGE_CANDIDATES = [
  process.env.EDGE_PATH,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/microsoft-edge',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
].filter(Boolean)

// ---------------------------------------------------------------------
// 夹具：46 篇同版次简报，id 越大越新
// ---------------------------------------------------------------------

const TOTAL = 46
const EDITION = 'market_watch_evening'
const REPORTS = Array.from({ length: TOTAL }, (_, i) => {
  const day = 20 - Math.floor(i / 1)
  const date = `2026-${i < 20 ? '11' : '10'}-${String(Math.max(1, day)).padStart(2, '0')}`
  return {
    id: 1000 - i,
    userId: 0,
    edition: EDITION,
    title: `【ETF市场数据简报晚间版】第 ${i + 1} 篇 沪深300ETF / 纳指100ETF / 标普500ETF ${date}`,
    summary: `第 ${i + 1} 篇：沪深300ETF 按计划买；纳指100ETF、标普500ETF 仓位备忘。`,
    content: `## 01 先看结论\n\n- 沪深300ETF：按计划买 | 4.058 | PE 13.09 | 分位 48\n- 纳指100ETF：按计划买 | 2.167 | PE 25.49 | 分位 15\n\n## 02 ETF变化\n\n- 沪深300ETF：今 4.058 | 昨 4.042 | +0.40%`,
    createdAt: '2026-11-20T10:00:00',
    displayTime: '18:00:00',
    reportDate: date,
  }
})

const apiResult = (status, message, data) => ({ code: status, message, data })

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
location.replace('/reports?edition=${EDITION}&page=3');
</script>`

const notStubbed = (path) => ({
  code: 500,
  message: `假接口没有为 ${path} 打桩——补上它，不要让页面把「没打桩」显示成「没取到数」`,
  data: null,
})

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const path = url.pathname
  const json = (status, body) => {
    res.writeHead(status, { 'Content-Type': MIME['.json'] })
    res.end(JSON.stringify(body))
  }

  if (path === '/__seed') {
    res.writeHead(200, { 'Content-Type': MIME['.html'] })
    res.end(SEED)
    return
  }

  if (path.startsWith('/api/')) {
    if (path === '/api/auth/me') {
      return json(200, apiResult(200, 'ok', {
        id: 1, email: 'demo@example.com', displayName: '演示账号',
        role: 'USER', accountType: 'DEMO',
      }))
    }
    if (path === '/api/reports') {
      const page = Number(url.searchParams.get('page') || '1')
      const size = Number(url.searchParams.get('size') || '10')
      const edition = url.searchParams.get('edition')
      const filtered = edition ? REPORTS.filter(r => r.edition === edition) : REPORTS
      const start = (page - 1) * size
      return json(200, apiResult(200, 'ok', {
        records: filtered.slice(start, start + size),
        total: filtered.length,
        pages: Math.ceil(filtered.length / size),
        current: page,
        size,
      }))
    }
    const nav = path.match(/^\/api\/reports\/(\d+)\/navigation$/)
    if (nav) {
      const id = Number(nav[1])
      const ids = REPORTS.map(r => r.id)
      return json(200, apiResult(200, 'ok', {
        edition: EDITION,
        ids,
        total: ids.length,
        index: ids.indexOf(id) + 1,
      }))
    }
    const detail = path.match(/^\/api\/reports\/(\d+)$/)
    if (detail) {
      const id = Number(detail[1])
      const report = REPORTS.find(r => r.id === id)
      if (!report) return json(200, apiResult(404, '简报不存在', null))
      return json(200, apiResult(200, 'ok', report))
    }
    return json(500, notStubbed(path))
  }

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
// CDP 小客户端（与 verify_analysis_page.mjs 同一套）
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

  async waitFor(expression, label, timeoutMs = 20000) {
    const deadline = Date.now() + timeoutMs
    for (;;) {
      if (await this.eval(expression)) return
      if (Date.now() > deadline) {
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
  if (!exe) throw new Error(`找不到 Edge。用 EDGE_PATH=<路径> 指一个，试过：${EDGE_CANDIDATES.join(', ')}`)
  const profile = join(tmpdir(), `report-pager-shots-${Date.now()}`)
  const child = spawn(exe, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`,
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

async function shoot(cdp, name, width = 1440, height = 1000) {
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile: width <= 480,
  })
  await sleep(250)
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
  const file = join(OUT, `${name}.png`)
  await writeFile(file, Buffer.from(shot.data, 'base64'))
  return file
}

const CLICK_BY_TEXT = (text) => `(() => {
  const button = [...document.querySelectorAll('button')]
    .find(b => b.textContent.includes(${JSON.stringify(text)}) && !b.disabled);
  if (!button) return false;
  button.click();
  return true;
})()`

const ACTIVE_PAGE = `(() => {
  const el = document.querySelector('.report-pager .ant-pagination-item-active');
  return el ? Number(el.textContent) : 0;
})()`

/** antd 的上一页/下一页是 li，不是 button。 */
const CLICK_NEXT = `(() => {
  const el = document.querySelector('.report-pager .ant-pagination-next:not(.ant-pagination-disabled)');
  if (!el) return false;
  el.click();
  return true;
})()`

const CLICK_DETAIL_BACK = `(() => {
  const el = document.querySelector('.detail-back');
  if (!el) return false;
  el.click();
  return true;
})()`

/** 用原生 setter 填值并按 Enter，模拟 antd 的「跳至 __ 页」（rc-pagination 监听 keyup）。 */
const QUICK_JUMP = (page) => `(() => {
  const input = document.querySelector('.report-pager .ant-pagination-options-quick-jumper input');
  if (!input) return false;
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(input, ${JSON.stringify(String(page))});
  input.dispatchEvent(new Event('input', { bubbles: true }));
  const event = new KeyboardEvent('keyup', {
    key: 'Enter', code: 'Enter', bubbles: true, cancelable: true,
  });
  Object.defineProperty(event, 'keyCode', { get: () => 13 });
  input.dispatchEvent(event);
  return true;
})()`

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function main() {
  if (!existsSync(join(DIST, 'index.html'))) {
    throw new Error(`找不到 ${DIST}/index.html。先跑：cd frontend && npm run build`)
  }
  await mkdir(OUT, { recursive: true })
  await new Promise((r) => server.listen(PORT, '127.0.0.1', r))
  const { child, profile } = await launchEdge(CDP_PORT)

  try {
    const { cdp, targetId } = await openTarget(CDP_PORT)
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')

    // 1. 历史简报第 3 页：URL 里的页码要真的作用于列表。
    await cdp.send('Page.navigate', { url: `http://127.0.0.1:${PORT}/__seed` })
    await cdp.waitFor('document.querySelectorAll(".report-card").length >= 10', '历史简报第 3 页')
    const firstOnPage3 = await cdp.eval(
      'document.querySelector(".report-card .report-title").textContent')
    assert(firstOnPage3.includes('第 21 篇'), `第 3 页第一篇应是第 21 篇，实际：${firstOnPage3}`)
    const activeOnList = await cdp.eval(
      'Number(document.querySelector(".history-pagination .ant-pagination-item-active").textContent)')
    assert(activeOnList === 3, `历史简报页码应为 3，实际：${activeOnList}`)
    const listShot = await shoot(cdp, 'history-page-3')

    // 2. 打开第 21 篇：详情页应给出 1..46 的页码与左右翻页。
    await cdp.eval('document.querySelector(".report-card").click()')
    await cdp.waitFor('!!document.querySelector(".report-pager")', '详情页页码条')
    await cdp.waitFor('!!document.querySelector(".report-article")', '简报正文')
    const openedPath = await cdp.eval('location.pathname')
    assert(/^\/report\/\d+$/.test(openedPath), `打开后应停在详情页，实际：${openedPath}`)
    const activeOnDetail = await cdp.eval(ACTIVE_PAGE)
    assert(activeOnDetail === 21, `详情页当前页码应为 21，实际：${activeOnDetail}`)
    const totalText = await cdp.eval(
      'document.querySelector(".report-pager .ant-pagination-total-text")?.textContent || ""')
    assert(totalText.includes('46'), `页码总数应显示 46，实际：${totalText}`)
    const hasQuickJumper = await cdp.eval(
      '!!document.querySelector(".report-pager .ant-pagination-options-quick-jumper input")')
    assert(hasQuickJumper, '详情页缺少「跳至 __ 页」输入框')
    const sideArrowVisible = await cdp.eval(
      '(() => { const el = document.querySelector(".report-side-next");'
      + ' return !!el && getComputedStyle(el).display !== "none"; })()')
    assert(sideArrowVisible, '1440 宽下右侧翻页圆钮应可见')
    const detailShot = await shoot(cdp, 'report-detail-page-21')

    // 3. 输入 46 跳转：应打开第 46 篇（最早的一篇），且「下一篇」不可用。
    assert(await cdp.eval(QUICK_JUMP(46)), '找不到跳页输入框')
    await cdp.waitFor(`${ACTIVE_PAGE} === 46`, '跳转到第 46 篇')
    await cdp.waitFor(
      'document.querySelector(".report-article h1")?.textContent.includes("第 46 篇")',
      '第 46 篇正文')
    const nextDisabled = await cdp.eval(
      'document.querySelector(".report-side-next")?.disabled === true')
    assert(nextDisabled, '第 46 篇时「下一篇」应禁用')
    const lastShot = await shoot(cdp, 'report-detail-page-46')

    // 4. 返回：要回到打开它的第 3 页，而不是第 1 页。
    assert(await cdp.eval(CLICK_DETAIL_BACK), '找不到详情页「返回」按钮')
    await cdp.waitFor('location.pathname === "/reports"', '返回历史简报')
    const search = await cdp.eval('location.search')
    assert(search.includes('page=3'), `返回后应带 page=3，实际：${search}`)
    assert(search.includes(`edition=${EDITION}`), `返回后应保留版次筛选，实际：${search}`)
    await cdp.waitFor('document.querySelectorAll(".report-card").length >= 10', '返回后的第 3 页列表')
    const activeAfterBack = await cdp.eval(
      'Number(document.querySelector(".history-pagination .ant-pagination-item-active").textContent)')
    assert(activeAfterBack === 3, `返回后页码应为 3，实际：${activeAfterBack}`)
    const firstAfterBack = await cdp.eval(
      'document.querySelector(".report-card .report-title").textContent')
    assert(firstAfterBack.includes('第 21 篇'), `返回后第一篇应是第 21 篇，实际：${firstAfterBack}`)
    const backShot = await shoot(cdp, 'history-back-to-page-3')

    // 5. 新标签直接打开详情链接（没有站内上一页）：翻一篇后返回应落到历史简报，而不是原地不动。
    const direct = await openTarget(CDP_PORT)
    try {
      await direct.cdp.send('Page.enable')
      await direct.cdp.send('Runtime.enable')
      await direct.cdp.send('Page.navigate', { url: `http://127.0.0.1:${PORT}/report/1000` })
      await direct.cdp.waitFor('!!document.querySelector(".report-pager")', '直接打开详情')
      if (!await direct.cdp.eval(CLICK_NEXT)) {
        const labels = await direct.cdp.eval(
          '[...document.querySelectorAll("button, .ant-pagination-prev, .ant-pagination-next")]'
          + '.map(b => b.textContent.trim())')
        throw new Error(`找不到详情页「下一篇」，现有元素：${JSON.stringify(labels)}`)
      }
      await direct.cdp.waitFor(`${ACTIVE_PAGE} === 2`, '直接打开后翻到第 2 篇')
      assert(await direct.cdp.eval(CLICK_DETAIL_BACK), '找不到详情页「返回」按钮')
      await direct.cdp.waitFor('location.pathname === "/reports"', '无上一页时返回历史简报')
      const directBackSearch = await direct.cdp.eval('location.search')
      assert(directBackSearch === '', `无上一页时返回不应携带旧页码，实际：${directBackSearch}`)
    } finally {
      await fetch(`http://127.0.0.1:${CDP_PORT}/json/close/${direct.targetId}`).catch(() => {})
    }

    // 6. 手机宽度：页码条要换行而不是撑出横向滚动，两侧圆钮应隐藏。
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: 390, height: 844, deviceScaleFactor: 1, mobile: true,
    })
    await sleep(400)
    await cdp.eval('document.querySelector(".report-card").click()')
    await cdp.waitFor('!!document.querySelector(".report-pager")', '手机详情页页码条')
    const quickJumperVisible = await cdp.eval(
      'getComputedStyle(document.querySelector(".report-pager .ant-pagination-options")).display !== "none"')
    assert(quickJumperVisible, '390px 下「跳至 __ 页」输入框被 antd 隐藏')
    const mobileOverflow = await cdp.eval(`(() => {
      const pager = document.querySelector('.report-pager');
      return {
        pageOverflow: document.documentElement.scrollWidth > window.innerWidth + 1,
        pagerOverflow: pager.scrollWidth > pager.clientWidth + 1,
        sideHidden: getComputedStyle(document.querySelector('.report-side-next')).display === 'none',
      };
    })()`)
    assert(!mobileOverflow.pageOverflow, '390px 下详情页出现整页横向滚动')
    assert(!mobileOverflow.pagerOverflow, '390px 下页码条撑出容器')
    assert(mobileOverflow.sideHidden, '390px 下两侧圆钮应隐藏')
    const mobileShot = await shoot(cdp, 'report-detail-mobile-390', 390, 900)

    console.log('✓ 详情页页码 1..46、跳页输入、左右翻页均正常')
    console.log('✓ 从第 3 页打开后返回，仍回到第 3 页')
    console.log('✓ 直接打开详情后翻页，返回落到历史简报')
    console.log('✓ 390px 下页码条换行、无横向溢出')
    console.log('\n截图：')
    for (const file of [listShot, detailShot, lastShot, backShot, mobileShot]) console.log(`  ${file}`)

    await fetch(`http://127.0.0.1:${CDP_PORT}/json/close/${targetId}`).catch(() => {})
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
