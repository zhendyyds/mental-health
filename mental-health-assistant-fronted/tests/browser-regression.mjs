import assert from 'node:assert/strict'
import { createServer as createHttpServer } from 'node:http'
import { mkdir, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { createServer as createViteServer } from 'vite'
import { chromium } from 'playwright'
import { eventually, startNginxFixture } from './nginx-fixture.mjs'

// Real Vue UI and HTTP/SSE transport, with fictional data and no model/database calls.
const root = fileURLToPath(new URL('..', import.meta.url))
const nginxBinary = process.env.NGINX_BIN
const fixtureDirectory = nginxBinary ? 'nginx-regression' : 'browser-regression'
const artifacts = resolve(process.env.BROWSER_ARTIFACT_DIR || resolve(root, '../mental-health-assistant-backend/target', fixtureDirectory))
await mkdir(artifacts, { recursive: true })
const sessionId = 81
const title = '浏览器回归会话'
const attack = '<img src=x onerror="window.__xss=1">\n原样文本'
const markdown = '**安全加粗**\n\n`code`\n\n[危险](javascript:alert(1))\n\n[编码](jav&#x61;script:alert(1))\n\n[站外相对](//evil.example/path)\n\n[正常](https://example.com/path)'
let history = []
let active = null
let starts = 0
let attaches = 0
let nextId = 100
let stopShouldFail = false
let stopPending = false
let deleted = false
let requests = new Map()
let streamBodies = []
let nextRequestId = 0
let unknownResponseAsHttp = false
let qaPosts = []
let qaHandler = null
let sourceAvailable = true
let indexRetried = 0
const emptyEmotion = () => ({ primaryEmotion: '中性', emotionScore: 50, riskLevel: 0, improvementSuggestions: [], analysisStatus: 'EMPTY', cached: false, stale: false, analyzedAt: null })
let emotionPayload = emptyEmotion()
let includeComparisonSession = false
let holdEmotionResponse = false
let deferredEmotionResponse = null
const watchers = new Set()
const results = []

const message = (content, senderType, requestId) => ({ id: ++nextId, sessionId, senderType, content, requestId, createdAt: '2026-11-05T10:00:00' })
const frame = (event, payload) => `event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`
const ok = (data) => ({ code: '200', msg: '操作成功', data })
const fragment = (text, requestId) => frame('message', ok({ content: text, type: 'normal', requestId }))
const json = (response, payload, status = 200, headers = {}) => {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers })
  response.end(JSON.stringify(payload))
}
const reset = (includeAttack = false) => {
  for (const watcher of watchers) watcher.end()
  watchers.clear()
  active = null
  starts = 0
  attaches = 0
  stopShouldFail = false
  stopPending = false
  deleted = false
  requests = new Map()
  streamBodies = []
  nextRequestId = 0
  unknownResponseAsHttp = false
  qaPosts = []
  qaHandler = null
  sourceAvailable = true
  indexRetried = 0
  emotionPayload = emptyEmotion()
  includeComparisonSession = false
  holdEmotionResponse = false
  deferredEmotionResponse?.end()
  deferredEmotionResponse = null
  history = includeAttack ? [message(attack, 1), message(markdown, 2)] : []
}
const observe = (response) => {
  response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache' })
  response.flushHeaders()
  if (active?.content) response.write(fragment(active.content, active.requestId))
  watchers.add(response)
  response.on('close', () => watchers.delete(response))
}
const append = (text) => {
  active.content += text
  for (const watcher of watchers) watcher.write(fragment(text, active.requestId))
}
const finish = (failure = false) => {
  assert.ok(active, 'fixture must have a running generation')
  const content = active.content
  const record = active
  if (!failure && content) history.push(message(content, 2, record.requestId))
  record.status = failure ? 'FAILED' : 'SUCCEEDED'
  record.saved = !failure && Boolean(content)
  active = null
  const terminal = failure
    ? frame('error', { code: '500', msg: '回复未能保存，请保留当前内容', data: { requestId: record.requestId, status: record.status, saved: false } })
    : frame('done', { requestId: record.requestId, status: record.status, saved: record.saved })
  for (const watcher of watchers) watcher.end(terminal)
  watchers.clear()
}
const finishUnknownPartial = () => {
  const record = active
  assert.ok(record)
  record.status = 'UNKNOWN'
  record.saved = true
  record.errorCode = '504'
  history = history.map(item => item.requestId === record.requestId
    ? { ...item, requestStatus: 'UNKNOWN', requestErrorCode: '504', requestErrorMessage: '回复生成超时', saved: true }
    : item)
  history.push({ ...message(record.content, 2, record.requestId), requestStatus: 'UNKNOWN', requestErrorCode: '504', requestErrorMessage: '回复生成超时', saved: true })
  active = null
  for (const watcher of watchers) watcher.end(frame('error', { code: '504', msg: '回复生成超时', data: { requestId: record.requestId, status: 'UNKNOWN', saved: true } }))
  watchers.clear()
}
const qaResult = (includeAttack = false) => ({
  requestId: 'fixture-qa-request',
  answer: includeAttack ? '试试缓慢呼吸。[1]\n<img src=x onerror="window.__xss=1">' : '可以先坐稳，放松肩膀，再慢慢呼气。练习时以舒服为准。[1]',
  grounded: true,
  citations: [{ id: '1', articleId: 'fixture-qa-article', revision: 2, title: '放松练习资料',
    excerpt: includeAttack ? '<b>先坐稳，再慢慢呼气。</b>' : '先坐稳，放松肩膀。慢慢呼气，保持舒适的节奏。' }]
})
const qaError = (response, code, status, headers) => json(response,
  { code: String(code), msg: '虚构问答状态', data: { requestId: 'fixture-qa-request', status } }, code, headers)

const api = createHttpServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://fixture.local')
    const path = url.pathname
    if (path === '/api/fixture/dns') {
      return json(response, { instance: 'original', uri: request.url, token: request.headers.token })
    }
    if (path === '/api/psychological-chat/stream' && url.searchParams.get('fixture') === 'heartbeat') {
      assert.equal(request.headers.token, 'fictional-browser-token')
      for await (const _chunk of request) { /* drain the fictional POST body */ }
      response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8' })
      response.write(': proxy-heartbeat\n\n')
      return
    }
    if (path === '/api/knowledge/article/page') {
      return json(response, ok({ total: 1, records: [{ id: 'fixture-qa-article', title: '代理封面样例', coverImage: '/upload/nginx-fixture-cover.svg',
        categoryName: '日常放松', authorName: '虚构作者', publishedAt: '2026-11-06T10:00:00', readCount: 2 }] }))
    }
    if (path === '/upload/nginx-fixture-cover.svg') {
      response.writeHead(200, { 'Content-Type': 'image/svg+xml' })
      return response.end('<svg xmlns="http://www.w3.org/2000/svg" width="12" height="8"><rect width="12" height="8" fill="navy"/></svg>')
    }
    if (path === '/api/knowledge/qa' && request.method === 'POST') {
      if (request.headers.token !== 'fictional-browser-token') return json(response, { code: '401', msg: '请登录' }, 401)
      let body = ''
      for await (const chunk of request) body += chunk
      const input = JSON.parse(body)
      qaPosts.push(input)
      return qaHandler ? qaHandler(response, input, request) : json(response, ok(qaResult()))
    }
    if (path === '/api/knowledge/article/fixture-qa-article') {
      if (!sourceAvailable) return json(response, { code: '404', msg: '文章已下线' })
      return json(response, ok({ title: '放松练习资料', categoryName: '日常放松', authorName: '虚构作者', readCount: 2,
        publishedAt: '2026-11-06T10:00:00', content: '<p>先坐稳，再慢慢呼气。</p><img src=x onerror="window.__xss=1">' }))
    }
    if (path.startsWith('/api/admin/knowledge/')) {
      if (request.headers.token !== 'fictional-admin-token') return json(response, { code: '403', msg: '无管理权限' }, 403)
      if (path.endsWith('/category/tree')) return json(response, ok([{ id: 1, categoryName: '测试分类' }]))
      if (path.endsWith('/article/page')) return json(response, ok({ total: 1, records: [{ id: 'fixture-qa-article', title: '放松练习资料', categoryId: 1, status: 1 }] }))
      if (path.endsWith('/index-jobs')) {
        assert.equal(url.searchParams.get('articleId'), 'fixture-qa-article')
        return json(response, ok([{ id: 700, articleId: 'fixture-qa-article', revision: 2, operation: 'BUILD',
          status: indexRetried ? 'PENDING' : 'FAILED', retryable: indexRetried === 0, errorCode: indexRetried ? null : 'INDEX_TEST_FAILURE' }]))
      }
      if (path.endsWith('/index-jobs/700/retry') && request.method === 'POST') {
        indexRetried++
        return json(response, ok({ id: 700, status: 'PENDING' }))
      }
    }
    if (path.endsWith('/fixture/business-error')) return json(response, { code: '-1', msg: '普通业务失败' })
    if (path.endsWith('/fixture/forbidden')) return json(response, { code: '403', msg: '没有权限' }, 403)
    if (path.endsWith('/fixture/unauthorized')) return json(response, { code: '401', msg: '登录已失效' }, 401)
    if (path.endsWith('/sessions') && request.method === 'GET') {
      const records = deleted ? [] : [{ id: sessionId, sessionTitle: title, startedAt: '2026-11-05', messageCount: history.length }]
      if (includeComparisonSession) records.push({ id: 82, sessionTitle: '切换状态对照会话', startedAt: '2026-11-06', messageCount: 0 })
      return json(response, ok({ records, total: records.length }))
    }
    if (path.endsWith('/stream/active')) return json(response, ok(active ? { sessionId, startedAt: 1 } : null))
    if (path.endsWith(`/sessions/${sessionId}/messages`)) return json(response, ok(history))
    if (path.endsWith('/sessions/82/messages')) return json(response, ok([]))
    if (path.endsWith('/emotion')) {
      if (path.includes('/session_82/')) return json(response, ok({ ...emptyEmotion(), primaryEmotion: '对照状态', emotionScore: 78, analysisStatus: 'SUCCEEDED', analyzedAt: '2026-11-06T12:00:00' }))
      if (holdEmotionResponse) { deferredEmotionResponse = response; return }
      return json(response, ok(emotionPayload))
    }
    if (path.endsWith('/stream/stop')) {
      if (stopPending) return json(response, { code: '409', msg: '停止请求已提交，结果待确认', data: { requestId: 'fixture-stopping', stopping: true } })
      if (stopShouldFail) {
        const requestId = active?.requestId
        if (active) finish(true)
        return json(response, { code: '500', msg: '回复未能保存，请保留当前内容', data: { requestId, status: 'FAILED', saved: false } })
      }
      if (active) finish()
      return json(response, ok(null))
    }
    if (path.endsWith(`/sessions/${sessionId}`) && request.method === 'DELETE') {
      deleted = true
      active = null
      for (const watcher of watchers) watcher.end(frame('done', {}))
      watchers.clear()
      return json(response, ok(null))
    }
    if (path.endsWith(`/stream/attach/${sessionId}`)) {
      attaches++
      if (active) return observe(response)
      response.writeHead(200, { 'Content-Type': 'text/event-stream' })
      return response.end(frame('error', { code: '404', msg: '没有正在生成的回复', data: { requestId: 'fixture-miss', saved: false } }))
    }
    if (path.endsWith('/stream')) {
      let body = ''
      for await (const chunk of request) body += chunk
      const input = JSON.parse(body)
      streamBodies.push(input)
      const reject = (code, msg, data = {}) => {
        response.writeHead(200, { 'Content-Type': 'text/event-stream' })
        response.end(frame('error', { code, msg, data }))
      }
      if (request.headers.token !== 'fictional-browser-token' || input.sessionId !== `session_${sessionId}` || deleted) {
        return reject('404', '会话不存在', { saved: false })
      }
      if (!/^[A-Za-z0-9_-]{8,128}$/.test(input.clientRequestId || '')) return reject('400', '缺少有效请求 ID', { saved: false })
      const fingerprint = JSON.stringify([input.sessionId, input.userMessage])
      const previous = requests.get(input.clientRequestId)
      if (previous) {
        const data = { requestId: previous.requestId, status: previous.status, saved: previous.saved, originalErrorCode: previous.errorCode }
        if (previous.fingerprint !== fingerprint) return reject('409', '同一请求 ID 的参数不一致', { ...data, status: 'CONFLICT' })
        if (previous.status === 'RUNNING') return observe(response)
        if (previous.status === 'SUCCEEDED') {
          response.writeHead(200, { 'Content-Type': 'text/event-stream' })
          return response.end(fragment(previous.content, previous.requestId) + frame('done', data))
        }
        if (previous.status === 'UNKNOWN') {
          if (unknownResponseAsHttp) return json(response, { code: '409', msg: '原请求结果待确认', data: { ...data, saved: previous.saved ?? null } }, 409)
          return reject('409', '原请求结果待确认', { ...data, saved: previous.saved ?? null })
        }
        return reject('500', '回复未能保存，请保留当前内容', data)
      }
      if (active) {
        return reject('409', '已有回复正在生成', { requestId: 'fixture-conflict', saved: false })
      }
      starts++
      active = { content: '', fingerprint, requestId: `fixture-request-${++nextRequestId}`, status: 'RUNNING' }
      requests.set(input.clientRequestId, active)
      history.push(message(input.userMessage, 1, active.requestId))
      observe(response)
      append('保留的回复片段')
      return
    }
    return json(response, { code: '404', msg: '测试接口不存在' }, 404)
  } catch (error) {
    if (!response.headersSent) json(response, { code: '500', msg: error.message }, 500)
    else response.end()
  }
})
await new Promise((resolveListen, reject) => {
  api.once('error', reject)
  api.listen(0, '127.0.0.1', resolveListen)
})
const nginx = nginxBinary ? await startNginxFixture({ binary: nginxBinary, root, artifacts, backendPort: api.address().port }).catch((error) => {
  api.closeAllConnections()
  api.close()
  throw error
}) : null
const vite = nginx ? null : await createViteServer({
  root,
  logLevel: 'error',
  server: { host: '127.0.0.1', port: 0, proxy: { '/api': { target: `http://127.0.0.1:${api.address().port}`, changeOrigin: true } } },
})
await vite?.listen()
const origin = nginx?.origin || `http://127.0.0.1:${vite.httpServer.address().port}`
const executablePath = process.env.BROWSER_EXECUTABLE_PATH || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(existsSync)
let browser
let context
let page
const check = async (name, action) => {
  const startedAt = Date.now()
  await action()
  results.push({ name, passed: true, milliseconds: Date.now() - startedAt })
  console.log(`PASS ${name}`)
}
const openSession = async (target = page) => {
  await target.goto(`${origin}/consultation`)
  await target.locator('.session-item').filter({ hasText: title }).click()
  await target.locator('textarea').waitFor()
}
const send = async () => {
  await page.locator('textarea').fill('虚构演示问题')
  await page.locator('textarea').press('Enter')
  await page.waitForFunction(() => document.querySelector('textarea')?.disabled === true)
  await page.locator('.ai-message .markdown-content').last().getByText('保留的回复片段', { exact: true }).waitFor()
}
const waitUnlocked = async (target = page) => {
  await target.waitForFunction(() => !document.querySelector('textarea')?.disabled)
}
const openQa = async () => {
  await page.goto(`${origin}/knowledge/qa`)
  await page.getByLabel('你的问题').waitFor()
}
const submitQa = async (question) => {
  await page.getByLabel('你的问题').fill(question)
  await page.getByRole('button', { name: '查找资料并回答', exact: true }).click()
}
const postStream = (input, { token = 'fictional-browser-token', firstFrameOnly = false } = {}) => page.evaluate(async ({ input, token, firstFrameOnly }) => {
  const response = await fetch('/api/psychological-chat/stream', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Token: token }, body: JSON.stringify(input)
  })
  if (!firstFrameOnly) return response.text()
  const reader = response.body.getReader()
  const { value } = await reader.read()
  await reader.cancel()
  return new TextDecoder().decode(value)
}, { input, token, firstFrameOnly })

try {
  browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) })
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  await context.addInitScript(() => {
    if (!localStorage.getItem('fixture-seeded')) {
      localStorage.setItem('token', 'fictional-browser-token')
      localStorage.setItem('userInfo', JSON.stringify({ userType: 1, username: 'browser-fixture' }))
      localStorage.setItem('fixture-seeded', 'true')
    }
    window.__xss = 0
  })
  page = await context.newPage()
  page.setDefaultTimeout(15000)

  if (nginx) {
    await check('真实 Nginx 保留 API 路径、查询和 token 并刷新后端 DNS', async () => {
      const uri = '/api/fixture/dns?check=first&keep=two%20words'
      const read = () => fetch(origin + uri, { headers: { Token: 'fictional-browser-token' }, signal: AbortSignal.timeout(1500) }).then(response => response.json())
      assert.deepEqual(await read(), { instance: 'original', uri, token: 'fictional-browser-token' })
      const replacement = createHttpServer((request, response) => json(response, { instance: 'replacement', uri: request.url, token: request.headers.token }))
      try {
        await new Promise((done, reject) => {
          replacement.once('error', reject)
          replacement.listen(api.address().port, '127.0.0.2', done)
        })
        nginx.changeBackendLoopback(2)
        await eventually(async () => {
          try {
            const actual = await read()
            if (actual.instance !== 'replacement') return false
            assert.deepEqual(actual, { instance: 'replacement', uri, token: 'fictional-browser-token' })
            return true
          } catch (error) {
            if (error instanceof assert.AssertionError) throw error
            return false
          }
        }, 'Nginx did not pick up the replacement backend address')
        nginx.changeBackendLoopback(1)
        await eventually(async () => (await read()).instance === 'original', 'Nginx did not return to the original fixture address')
      } finally {
        nginx.changeBackendLoopback(1)
        replacement.closeAllConnections()
        await new Promise((done) => replacement.close(done))
      }
    })

    await check('真实 Nginx 在空闲 SSE 连接立即交付心跳', async () => {
      const response = await fetch(`${origin}/api/psychological-chat/stream?fixture=heartbeat`, {
        method: 'POST', headers: { Token: 'fictional-browser-token', 'Content-Type': 'application/json' }, body: '{}',
        signal: AbortSignal.timeout(3000)
      })
      assert.equal(response.status, 200)
      assert.equal(response.headers.get('x-accel-buffering'), 'no')
      assert.match(response.headers.get('cache-control'), /no-store/)
      const reader = response.body.getReader()
      try {
        const decoder = new TextDecoder()
        let heartbeat = ''
        while (!heartbeat.includes('\n\n')) {
          const frame = await reader.read()
          assert.equal(frame.done, false)
          heartbeat += decoder.decode(frame.value, { stream: true })
        }
        assert.equal(heartbeat, ': proxy-heartbeat\n\n')
      } finally { await reader.cancel() }
    })

    await check('生产浏览器随机宿主端口加载同源封面且刷新子路由', async () => {
      await page.goto(`${origin}/knowledge`)
      const cover = page.locator('.article-item img[src="/upload/nginx-fixture-cover.svg"]')
      await cover.waitFor()
      await page.waitForFunction(() => document.querySelector('.article-item img')?.naturalWidth === 12)
      assert.equal(await cover.evaluate(image => new URL(image.src).origin), origin)
      await page.locator('.article-item').click()
      await page.waitForURL('**/knowledge/article/fixture-qa-article')
      await page.reload()
      await page.getByRole('heading', { name: '放松练习资料' }).waitFor()
      assert.equal((await fetch(`${origin}/internal/health/ready`)).status, 404)
      assert.equal((await fetch(`${origin}/assets/missing.js`)).status, 404)
    })

    await check('Nginx 观察连接全部断开后可回查完整回复', async () => {
      reset()
      await openSession()
      await send()
      assert.equal(watchers.size, 1)
      await page.goto(origin, { waitUntil: 'domcontentloaded' })
      await eventually(() => watchers.size === 0, 'Closing the page did not close the upstream SSE observer')
      assert.ok(active)
      append('，断开后完成。')
      finish()
      await openSession()
      await page.locator('.ai-message .markdown-content').getByText('保留的回复片段，断开后完成。', { exact: true }).waitFor()
      assert.equal(starts, 1)
      assert.equal(history.filter((item) => item.senderType === 2).length, 1)
    })
  }

  await check('用户文本与模型 Markdown 安全渲染', async () => {
    reset(true)
    await openSession()
    assert.equal(await page.locator('.user-text').textContent(), attack)
    assert.equal(await page.locator('.user-message img').count(), 1, 'only the controlled avatar image exists')
    assert.equal(await page.locator('.user-text img').count(), 0)
    await page.locator('.markdown-content strong').waitFor()
    assert.equal(await page.locator('.markdown-content strong').textContent(), '安全加粗')
    const links = await page.locator('.markdown-content a').evaluateAll((nodes) => nodes.map((node) => node.getAttribute('href')))
    assert.ok(links.includes('https://example.com/path'))
    assert.ok(links.every((href) => href && !/^(?:javascript|data|vbscript):|^\/\//i.test(href)))
    assert.equal(await page.locator('.message-bubble [onerror], .message-bubble [onclick], .message-bubble script').count(), 0)
    assert.equal(await page.evaluate(() => window.__xss), 0)
  })

  await check('正常完成仅生成和保存一次', async () => {
    reset()
    await openSession()
    await send()
    append('，正常完成。')
    finish()
    await waitUnlocked()
    assert.equal(starts, 1)
    assert.equal(history.filter((item) => item.senderType === 2).length, 1)
    assert.equal(await page.locator('.ai-message .markdown-content').textContent().then((text) => text.trim()), '保留的回复片段，正常完成。')
  })

  await check('保存失败保留片段和请求 ID', async () => {
    reset()
    await openSession()
    await send()
    const requestId = active.requestId
    finish(true)
    await page.locator('.error-message').filter({ hasText: requestId }).waitFor()
    assert.match(await page.locator('.error-message').textContent(), /未保存/)
    assert.match(await page.locator('.ai-message .markdown-content').textContent(), /保留的回复片段/)
    await waitUnlocked()
    assert.equal(starts, 1)
    assert.equal(history.filter((item) => item.senderType === 2).length, 0)
    await page.screenshot({ path: resolve(artifacts, 'save-failure.png'), fullPage: true })
  })

  await check('刷新接回完整回放且不重新生成', async () => {
    reset()
    await openSession()
    await send()
    await openSession()
    await page.getByText('保留的回复片段', { exact: true }).waitFor()
    assert.equal(starts, 1)
    assert.ok(attaches >= 1)
    append('，刷新后继续。')
    finish()
    await waitUnlocked()
    const content = await page.locator('.ai-message .markdown-content').textContent()
    assert.equal((content.match(/保留的回复片段/g) || []).length, 1)
  })

  await check('两个标签页重连与停止一致', async () => {
    reset()
    await openSession()
    await send()
    const second = await context.newPage()
    try {
      await openSession(second)
      await second.getByText('保留的回复片段', { exact: true }).waitFor()
      assert.equal(await second.locator('textarea').isDisabled(), true)
      await second.getByTitle('停止生成').click()
      await waitUnlocked()
      await waitUnlocked(second)
      assert.equal(starts, 1)
      assert.equal(history.filter((item) => item.senderType === 2).length, 1)
      assert.match(await page.locator('.ai-message .markdown-content').textContent(), /保留的回复片段/)
    } finally { await second.close() }
  })

  await check('传输提前关闭只 attach 不重发生成', async () => {
    reset()
    await openSession()
    await send()
    for (const watcher of watchers) watcher.end()
    watchers.clear()
    await page.waitForFunction(() => document.querySelector('textarea')?.disabled === true)
    await assert.doesNotReject(async () => {
      const deadline = Date.now() + 15000
      while (attaches === 0 && Date.now() < deadline) await new Promise((resolveWait) => setTimeout(resolveWait, 30))
      assert.ok(attaches >= 1, 'transport recovery must attach to the existing reply')
    })
    append('，断开后继续。')
    finish()
    await waitUnlocked()
    assert.equal(starts, 1)
    const content = await page.locator('.ai-message .markdown-content').textContent()
    assert.equal((content.match(/保留的回复片段/g) || []).length, 1)
  })

  await check('停止时保存失败仍保留片段', async () => {
    reset()
    await openSession()
    await send()
    const requestId = active.requestId
    stopShouldFail = true
    await page.getByTitle('停止生成').click()
    await page.locator('.error-message').filter({ hasText: requestId }).waitFor()
    await waitUnlocked()
    assert.match(await page.locator('.ai-message .markdown-content').textContent(), /保留的回复片段/)
    assert.match(await page.locator('.error-message').textContent(), /未保存/)
    assert.equal(history.filter((item) => item.senderType === 2).length, 0)
    assert.equal(starts, 1)
  })

  await check('终止事件丢失且无落库结果时保留未确认片段', async () => {
    reset()
    await openSession()
    await send()
    const reloaded = page.waitForResponse((response) => response.url().endsWith(`/sessions/${sessionId}/messages`))
    active = null
    for (const watcher of watchers) watcher.end()
    watchers.clear()
    await reloaded
    await page.locator('.error-message').filter({ hasText: '待确认' }).waitFor()
    await waitUnlocked()
    assert.match(await page.locator('.ai-message .markdown-content').textContent(), /保留的回复片段/)
    assert.equal(starts, 1)
    assert.equal(history.filter((item) => item.senderType === 2).length, 0)
  })

  await check('停止待确认保持观察和输入锁定', async () => {
    reset()
    await openSession()
    await send()
    stopPending = true
    await page.getByTitle('停止生成').click()
    await page.getByText('停止已提交，正在确认结果', { exact: true }).waitFor()
    assert.equal(await page.locator('textarea').isDisabled(), true)
    assert.match(await page.locator('.ai-message .markdown-content').textContent(), /保留的回复片段/)
    finish()
    await waitUnlocked()
    assert.equal(starts, 1)
    assert.equal(await page.locator('.error-message').count(), 0)
  })

  await check('删除生成中会话丢弃残句并恢复输入', async () => {
    reset()
    await openSession()
    await send()
    await page.locator('.session-actions button').click()
    await page.waitForFunction(() => document.querySelectorAll('.session-item').length === 0)
    await waitUnlocked()
    assert.equal(active, null)
    assert.equal(starts, 1)
    assert.equal(history.filter((item) => item.senderType === 2).length, 0)
    assert.equal(await page.locator('.user-message').count(), 0)
  })

  await check('未分析占位不显示中性评分', async () => {
    reset()
    await openSession()
    await page.locator('.analysis-state').filter({ hasText: '尚未分析' }).waitFor()
    assert.equal(await page.locator('.emotion-name').textContent(), '暂无可用分析')
    assert.equal(await page.locator('.emotion-score').count(), 0)
  })

  await check('分析超时保留旧快照并明确标记陈旧', async () => {
    reset()
    emotionPayload = { ...emptyEmotion(), primaryEmotion: '平静', emotionScore: 60, analysisStatus: 'TIMED_OUT', cached: true, stale: true, analyzedAt: '2026-11-06T11:00:00', coveredMessageId: 1 }
    await openSession()
    await page.locator('.analysis-state').filter({ hasText: '分析超时' }).waitFor()
    assert.match(await page.locator('.analysis-state').textContent(), /旧结果.*未覆盖最新消息/)
    assert.equal(await page.locator('.emotion-score').textContent(), '60')
    await page.screenshot({ path: resolve(artifacts, 'analysis-stale.png'), fullPage: true })
  })

  await check('分析拒绝不冒充新的模型结果', async () => {
    reset()
    emotionPayload = { ...emptyEmotion(), analysisStatus: 'REJECTED' }
    await openSession()
    await page.locator('.analysis-state').filter({ hasText: '额度不足' }).waitFor()
    assert.equal(await page.locator('.emotion-score').count(), 0)
  })

  await check('切换会话后迟到分析不覆盖当前卡片', async () => {
    reset()
    includeComparisonSession = true
    holdEmotionResponse = true
    await openSession()
    const deadline = Date.now() + 15000
    while (!deferredEmotionResponse && Date.now() < deadline) await new Promise((resolveWait) => setTimeout(resolveWait, 20))
    assert.ok(deferredEmotionResponse, 'the first session analysis must be in flight')
    await page.locator('.session-item').filter({ hasText: '切换状态对照会话' }).click()
    await page.locator('.emotion-name').filter({ hasText: '对照状态' }).waitFor()
    const arrived = page.waitForResponse((response) => response.url().endsWith('/session/session_81/emotion'))
    json(deferredEmotionResponse, ok({ ...emptyEmotion(), primaryEmotion: '迟到旧状态', emotionScore: 10, analysisStatus: 'SUCCEEDED', analyzedAt: '2026-11-06T11:00:00' }))
    deferredEmotionResponse = null
    holdEmotionResponse = false
    await arrived
    await page.evaluate(() => new Promise((resolvePaint) => requestAnimationFrame(() => requestAnimationFrame(resolvePaint))))
    assert.equal(await page.locator('.emotion-name').textContent(), '对照状态')
    assert.equal(await page.locator('.emotion-score').textContent(), '78')
  })

  await check('明确新问题生成不同请求 ID', async () => {
    reset()
    await openSession()
    await send()
    const first = streamBodies[0].clientRequestId
    assert.match(first, /^[a-f0-9-]{36}$/)
    finish()
    await waitUnlocked()
    await send()
    assert.notEqual(streamBodies[1].clientRequestId, first)
    finish()
    await waitUnlocked()
    assert.equal(starts, 2)
  })

  await check('同 ID 处理中重复提交只观看原回复', async () => {
    reset()
    await openSession()
    await send()
    const input = { ...streamBodies[0] }
    const requestId = active.requestId
    const duplicate = await postStream(input, { firstFrameOnly: true })
    assert.match(duplicate, /保留的回复片段/)
    assert.ok(duplicate.includes(requestId))
    assert.equal(starts, 1)
    assert.equal(history.filter(item => item.senderType === 1).length, 1)
    finish()
    await waitUnlocked()
    assert.equal(history.filter(item => item.senderType === 2).length, 1)
  })

  await check('同 ID 成功重放后页面回源无重复气泡', async () => {
    reset()
    await openSession()
    await send()
    const input = { ...streamBodies[0] }
    finish()
    await waitUnlocked()
    await openSession()
    await page.evaluate(id => Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => id }), input.clientRequestId)
    await page.locator('textarea').fill(input.userMessage)
    await page.locator('textarea').press('Enter')
    await waitUnlocked()
    await page.waitForFunction(() => document.querySelectorAll('.user-message').length === 1 && document.querySelectorAll('.ai-message').length === 1)
    assert.equal(starts, 1)
    assert.equal(history.length, 2)
    const content = await page.locator('.ai-message .markdown-content').textContent()
    assert.equal((content.match(/保留的回复片段/g) || []).length, 1)
  })

  await check('同 ID 参数冲突拒绝且保留原请求结果', async () => {
    reset()
    await openSession()
    await send()
    const input = { ...streamBodies[0] }
    const originalRequestId = active.requestId
    const conflict = await postStream({ ...input, userMessage: '篡改后的问题' })
    assert.match(conflict, /CONFLICT/)
    assert.match(conflict, /409/)
    assert.ok(conflict.includes(originalRequestId))
    assert.equal(starts, 1)
    assert.equal(history.filter(item => item.senderType === 1).length, 1)
    finish()
    await waitUnlocked()
  })

  await check('同 ID 已失败请求返回原失败且不重启模型', async () => {
    reset()
    await openSession()
    await send()
    const input = { ...streamBodies[0] }
    const originalRequestId = active.requestId
    finish(true)
    await waitUnlocked()
    const failure = await postStream(input)
    assert.match(failure, /FAILED/)
    assert.ok(failure.includes(originalRequestId))
    assert.equal(starts, 1)
    assert.equal(history.filter(item => item.senderType === 1).length, 1)
    assert.equal(history.filter(item => item.senderType === 2).length, 0)
  })

  await check('未知状态明确待确认且不自动重新生成', async () => {
    reset()
    const input = { sessionId: `session_${sessionId}`, userMessage: '虚构演示问题', clientRequestId: 'fixture-unknown-client' }
    requests.set(input.clientRequestId, { fingerprint: JSON.stringify([input.sessionId, input.userMessage]), requestId: 'fixture-unknown-request', status: 'UNKNOWN', saved: null })
    await openSession()
    await page.evaluate(id => Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => id }), input.clientRequestId)
    await page.locator('textarea').fill(input.userMessage)
    await page.locator('textarea').press('Enter')
    await page.locator('.error-message').filter({ hasText: 'fixture-unknown-request' }).waitFor()
    assert.match(await page.locator('.error-message').textContent(), /保存状态待确认.*不要重复发送/)
    await waitUnlocked()
    assert.equal(starts, 0)
    assert.equal(streamBodies.length, 1)
    assert.equal(history.length, 0)
    await page.screenshot({ path: resolve(artifacts, 'request-unknown.png'), fullPage: true })
  })

  await check('另一用户复用请求 ID 不能观看或重放他人结果', async () => {
    reset()
    await openSession()
    await send()
    const input = { ...streamBodies[0] }
    finish()
    await waitUnlocked()
    const denied = await postStream(input, { token: 'fictional-other-user-token' })
    assert.match(denied, /404/)
    assert.ok(!denied.includes('保留的回复片段'))
    assert.ok(!denied.includes('fixture-request-1'))
    assert.equal(starts, 1)
    assert.equal(history.length, 2)
  })

  await check('HTTP 未知状态仍显示保存待确认', async () => {
    reset()
    unknownResponseAsHttp = true
    const input = { sessionId: `session_${sessionId}`, userMessage: '虚构演示问题', clientRequestId: 'fixture-http-unknown-client' }
    requests.set(input.clientRequestId, { fingerprint: JSON.stringify([input.sessionId, input.userMessage]), requestId: 'fixture-http-unknown-request', status: 'UNKNOWN', saved: null })
    await openSession()
    await page.evaluate(id => Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => id }), input.clientRequestId)
    await page.locator('textarea').fill(input.userMessage)
    await page.locator('textarea').press('Enter')
    await page.locator('.error-message').filter({ hasText: 'fixture-http-unknown-request' }).waitFor()
    assert.match(await page.locator('.error-message').textContent(), /保存状态待确认.*不要重复发送/)
    await waitUnlocked()
    assert.equal(starts, 0)
    assert.equal(streamBodies.length, 1)
  })

  await check('未知上游结果的已保存片段刷新后仍保留状态', async () => {
    reset()
    await openSession()
    await send()
    const requestId = active.requestId
    finishUnknownPartial()
    await page.locator('.error-message').filter({ hasText: requestId }).waitFor()
    assert.match(await page.locator('.error-message').textContent(), /当前片段已保存.*生成超时.*结果待确认/)
    await waitUnlocked()
    await openSession()
    await page.locator('.error-message').filter({ hasText: requestId }).waitFor()
    assert.match(await page.locator('.error-message').textContent(), /当前片段已保存.*生成超时.*结果待确认/)
    assert.equal(await page.locator('.error-message').count(), 1)
    assert.match(await page.locator('.ai-message .markdown-content').textContent(), /保留的回复片段/)
    assert.equal(await page.locator('.ai-message').count(), 1)
    assert.equal(starts, 1)
    assert.equal(history.filter(item => item.senderType === 2).length, 1)
    await page.screenshot({ path: resolve(artifacts, 'partial-unknown-saved.png'), fullPage: true })
  })

  await check('未知请求没有 AI 消息时刷新仍显示回复状态', async () => {
    reset()
    history.push({ ...message('已保存的虚构问题', 1, 'fixture-unanswered-request'), requestStatus: 'UNKNOWN', requestErrorCode: '409', requestErrorMessage: '模型执行结果待确认', saved: false })
    await openSession()
    await page.locator('.user-message .error-message').filter({ hasText: 'fixture-unanswered-request' }).waitFor()
    assert.equal(await page.locator('.user-text').textContent(), '已保存的虚构问题')
    assert.match(await page.locator('.error-message').textContent(), /AI回复状态：.*待确认.*不要重复发送/)
    assert.ok(!(await page.locator('.error-message').textContent()).includes('未保存'))
    assert.equal(await page.locator('.ai-message').count(), 0)
    await openSession()
    await page.locator('.user-message .error-message').filter({ hasText: 'fixture-unanswered-request' }).waitFor()
    assert.equal(await page.locator('.error-message').count(), 1)
    assert.equal(await page.locator('.ai-message').count(), 0)
    assert.equal(starts, 0)
    assert.equal(streamBodies.length, 0)
    await page.screenshot({ path: resolve(artifacts, 'unanswered-unknown.png'), fullPage: true })
  })

  await check('知识问答入口要求登录', async () => {
    reset()
    const guest = await browser.newPage()
    try {
      await guest.goto(`${origin}/knowledge/qa`)
      await guest.waitForURL('**/auth/login')
      assert.equal(await guest.getByLabel('你的问题').count(), 0)
      assert.equal(qaPosts.length, 0)
    } finally { await guest.close() }
  })

  await check('独立问答防重复发送并安全展示资料与原文链接', async () => {
    reset()
    let pendingResponse
    qaHandler = (response) => { pendingResponse = response }
    await page.goto(origin)
    await page.getByRole('link', { name: '知识问答', exact: true }).click()
    await submitQa('如何进行日常放松？')
    await page.getByRole('status').filter({ hasText: '正在查找资料' }).waitFor()
    await page.evaluate(() => document.querySelector('.qa-form').requestSubmit())
    await page.waitForFunction(() => document.querySelector('#qa-question')?.disabled)
    assert.equal(qaPosts.length, 1)
    assert.deepEqual(Object.keys(qaPosts[0]).sort(), ['clientRequestId', 'question'])
    assert.match(qaPosts[0].clientRequestId, /^[a-f0-9-]{36}$/)
    json(pendingResponse, ok(qaResult(true)))
    await page.getByRole('heading', { name: '资料回答', exact: true }).waitFor()
    assert.match(await page.locator('.qa-answer-text').textContent(), /<img/)
    assert.equal(await page.locator('.qa-answer-text img').count(), 0)
    assert.equal(await page.locator('.qa-sources blockquote b').count(), 0)
    assert.equal(await page.evaluate(() => window.__xss), 0)
    const source = page.getByRole('link', { name: /放松练习资料/ })
    assert.equal(await source.getAttribute('href'), '/knowledge/article/fixture-qa-article')
    const [article] = await Promise.all([context.waitForEvent('page'), source.click()])
    try {
      await article.getByRole('heading', { name: '放松练习资料', exact: true }).waitFor()
      assert.equal(await article.locator('.content-wrapper [onerror]').count(), 0)
      assert.equal(await article.evaluate(() => window.__xss), 0)
    } finally { await article.close() }
    assert.equal(await page.getByLabel('你的问题').inputValue(), '如何进行日常放松？')
  })

  await check('问答断网保留问题并沿用原 ID 重试，明确确认才发起新请求', async () => {
    reset()
    let original
    await page.route('**/api/knowledge/qa', async (route) => {
      original = route.request().postDataJSON()
      await route.abort('failed')
    }, { times: 1 })
    qaHandler = (response) => {
      if (qaPosts.length === 1) return qaError(response, 409, 'RUNNING')
      if (qaPosts.length === 2) return qaError(response, 409, 'UNKNOWN')
      return json(response, ok(qaResult()))
    }
    await openQa()
    await submitQa('原来的问题')
    await page.getByRole('status').filter({ hasText: '连接中断，结果待确认' }).waitFor()
    assert.equal(await page.getByLabel('你的问题').inputValue(), '原来的问题')
    assert.equal(qaPosts.length, 0)
    await page.getByLabel('你的问题').fill('编辑后的问题')
    const lookup = page.getByRole('button', { name: '重试原请求', exact: true })
    await lookup.click()
    await page.getByRole('status').filter({ hasText: '原请求仍在处理' }).waitFor()
    assert.deepEqual(qaPosts[0], original)
    await lookup.click()
    await page.getByRole('status').filter({ hasText: '原请求结果待确认' }).waitFor()
    assert.deepEqual(qaPosts[1], original)
    assert.equal(await page.getByLabel('你的问题').inputValue(), '编辑后的问题')
    const submitNew = page.locator('.qa-form').getByRole('button', { name: '发起新请求', exact: true })
    const confirmDialog = page.getByRole('dialog', { name: '发起新请求' })
    await submitNew.click()
    await confirmDialog.getByRole('button', { name: '返回', exact: true }).click()
    await confirmDialog.waitFor({ state: 'hidden' })
    assert.equal(qaPosts.length, 2)
    await submitNew.click()
    await confirmDialog.getByRole('button', { name: '发起新请求', exact: true }).click()
    await page.getByRole('heading', { name: '资料回答', exact: true }).waitFor()
    assert.equal(qaPosts.length, 3)
    assert.notEqual(qaPosts[2].clientRequestId, original.clientRequestId)
    assert.equal(qaPosts[2].question, '编辑后的问题')
  })

  await check('问答区分无证据、限流、服务不可用和超时', async () => {
    reset()
    qaHandler = (response) => json(response, ok({ requestId: 'no-evidence', answer: '没有足够资料回答这个问题。', grounded: false, citations: [] }))
    await openQa()
    await submitQa('查找资料')
    await page.getByRole('heading', { name: '未找到足够资料', exact: true }).waitFor()
    assert.equal(await page.locator('.qa-sources').count(), 0)
    for (const [code, status, title, replay] of [[429, 'FAILED', '请求过于频繁', false], [503, 'FAILED', '知识问答暂不可用', false], [504, 'UNKNOWN', '问答超时，结果待确认', true]]) {
      qaHandler = (response) => qaError(response, code, status, code === 429 ? { 'Retry-After': '12' } : {})
      await openQa()
      await submitQa('需要保留的问题')
      await page.getByRole('status').filter({ hasText: title }).waitFor()
      if (code === 429) assert.match(await page.locator('.qa-feedback').textContent(), /12 秒/)
      assert.equal(await page.getByLabel('你的问题').inputValue(), '需要保留的问题')
      assert.equal(await page.getByRole('button', { name: '重试原请求', exact: true }).count(), Number(replay))
      assert.equal(await page.locator('.qa-result').count(), 0)
    }
    assert.equal(qaPosts.length, 4)
  })

  await check('来源下线后文章和原回答均明确提示', async () => {
    reset()
    await openQa()
    await submitQa('如何放松')
    await page.getByRole('heading', { name: '资料回答', exact: true }).waitFor()
    await page.screenshot({ path: resolve(artifacts, 'qa-answer.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true)
    await page.screenshot({ path: resolve(artifacts, 'qa-answer-mobile.png'), fullPage: true })
    await page.setViewportSize({ width: 1440, height: 1000 })
    sourceAvailable = false
    const [article] = await Promise.all([context.waitForEvent('page'), page.getByRole('link', { name: /放松练习资料/ }).click()])
    try { await article.getByRole('status').filter({ hasText: '这篇文章已下线或不可查看' }).waitFor() }
    finally { await article.close() }
    qaHandler = (response) => qaError(response, 409, 'SOURCE_CHANGED')
    await page.getByRole('button', { name: '核对原结果', exact: true }).click()
    await page.getByRole('status').filter({ hasText: '来源已变化' }).waitFor()
    assert.deepEqual(qaPosts[1], qaPosts[0])
    assert.equal(await page.locator('.qa-result').count(), 0)
    assert.equal(await page.getByLabel('你的问题').inputValue(), '如何放松')
    await page.screenshot({ path: resolve(artifacts, 'qa-source-changed.png'), fullPage: true })
  })

  await check('临时结果过期后保留问题并停止原 ID 查询', async () => {
    reset()
    await openQa()
    await submitQa('如何放松')
    await page.getByRole('heading', { name: '资料回答', exact: true }).waitFor()
    qaHandler = (response) => qaError(response, 409, 'RESULT_UNAVAILABLE')
    await page.getByRole('button', { name: '核对原结果', exact: true }).click()
    await page.getByRole('status').filter({ hasText: '原结果已无法查看' }).waitFor()
    assert.deepEqual(qaPosts[1], qaPosts[0])
    assert.equal(await page.getByRole('button', { name: /核对原结果|重试原请求/ }).count(), 0)
    assert.equal(await page.locator('.qa-result').count(), 0)
    assert.equal(await page.getByLabel('你的问题').inputValue(), '如何放松')
  })

  await check('管理端查看索引失败并显式重试', async () => {
    reset()
    const admin = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
    await admin.addInitScript(() => {
      localStorage.setItem('token', 'fictional-admin-token')
      localStorage.setItem('userInfo', JSON.stringify({ userType: 2, username: 'browser-admin-fixture' }))
    })
    const adminPage = await admin.newPage()
    try {
      await adminPage.goto(`${origin}/back/knowledge`)
      await adminPage.getByRole('button', { name: '查看任务', exact: true }).click()
      const tasks = adminPage.getByRole('dialog', { name: '文章索引任务' })
      await tasks.getByText('INDEX_TEST_FAILURE', { exact: true }).waitFor()
      await tasks.getByRole('button', { name: '重试', exact: true }).click()
      await tasks.getByText('排队中', { exact: true }).waitFor()
      assert.equal(indexRetried, 1)
      assert.equal(await tasks.getByRole('button', { name: '重试', exact: true }).count(), 0)
      await adminPage.screenshot({ path: resolve(artifacts, 'qa-admin-index.png'), fullPage: true })
    } finally { await admin.close() }
  })

  // The direct source import is a dev-server test. The production bundle is
  // exercised through the actual UI in the other cases.
  if (!nginx) await check('业务失败和 403 不清理登录，401 清理登录', async () => {
    const call = (path) => page.evaluate(async (apiPath) => {
      const { default: service } = await import('/src/utils/request.js')
      try { await service.get(apiPath); return { rejected: false } }
      catch (error) { return { rejected: true, code: error.code, token: localStorage.getItem('token') } }
    }, path)
    const business = await call('/fixture/business-error')
    assert.equal(business.rejected, true)
    assert.equal(business.code, '-1')
    assert.ok(business.token)
    const forbidden = await call('/fixture/forbidden')
    assert.equal(forbidden.rejected, true)
    assert.ok(forbidden.token)
    const unauthorized = await call('/fixture/unauthorized')
    assert.equal(unauthorized.rejected, true)
    assert.equal(unauthorized.token, null)
    await page.waitForURL('**/auth/login', { waitUntil: 'domcontentloaded' })
  })
} catch (error) {
  results.push({ name: 'failure', passed: false, message: error.message })
  if (page) await page.screenshot({ path: resolve(artifacts, 'failure.png'), fullPage: true }).catch(() => {})
  process.exitCode = 1
  console.error(error)
} finally {
  const environment = nginx ? 'production Vue build + real Nginx + fictional local HTTP/SSE fixture' : 'actual Vue UI + fictional local HTTP/SSE fixture'
  await writeFile(resolve(artifacts, 'results.json'), JSON.stringify({ environment, node: process.version, browser: browser?.version(), nginxSyntax: nginx?.syntax, results }, null, 2))
  // Closing the browser also closes its contexts, including a pending 401 redirect.
  await browser?.close()
  for (const watcher of watchers) watcher.end()
  deferredEmotionResponse?.end()
  api.closeAllConnections()
  await vite?.close()
  await nginx?.close()
  await new Promise((resolveClose) => api.close(resolveClose))
  console.log('Browser regression complete')
}
