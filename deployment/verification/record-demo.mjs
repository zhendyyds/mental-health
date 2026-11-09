import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { copyFile, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { setTimeout as pause } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const repository = fileURLToPath(new URL('../../', import.meta.url))
const { values } = parseArgs({ options: {
  origin: { type: 'string' }, 'fixture-state': { type: 'string' }, output: { type: 'string' }, help: { type: 'boolean' },
} })
const usage = 'node deployment/verification/record-demo.mjs --origin http://127.0.0.1:<port> [--fixture-state path/to/fixture-state.json] [--output artifact-directory]'
const width = 1440
const height = 900

function runBinary(binary, args) {
  const process = spawnSync(binary, args, { encoding: 'utf8', windowsHide: true, timeout: 60_000 })
  if (process.error) throw process.error
  assert.equal(process.status, 0, process.stderr)
  return process.stdout
}

// Record the actual browser compositor. A new tab can take over the same timeline.
function createScreencast(context, scratch) {
  const frames = []
  const writes = []
  let target
  let writeFailure

  async function stop() {
    if (!target) return
    await target.send('Page.stopScreencast')
    await target.detach()
    target = undefined
  }

  return {
    stop,
    async follow(page) {
      await stop()
      await page.bringToFront()
      const session = await context.newCDPSession(page)
      target = session
      let previous = -Infinity
      session.on('Page.screencastFrame', ({ data, sessionId }) => {
        void session.send('Page.screencastFrameAck', { sessionId }).catch(() => {})
        const time = performance.now()
        if (time - previous < 70) return
        previous = time
        const name = `frame-${String(frames.length).padStart(5, '0')}.jpg`
        frames.push({ name, time })
        writes.push(writeFile(resolve(scratch, name), Buffer.from(data, 'base64')).catch((error) => { writeFailure ||= error }))
      })
      await session.send('Page.startScreencast', { format: 'jpeg', quality: 85, maxWidth: width, maxHeight: height })
    },
    async encode(ffmpeg, video) {
      await stop()
      await Promise.all(writes)
      if (writeFailure) throw writeFailure
      assert.ok(frames.length >= 6, 'Browser capture contains too few frames')
      const manifest = frames.map((frame, index) => {
        const following = frames[index + 1]?.time ?? performance.now()
        const seconds = Math.min(3, Math.max(1 / 24, (following - frame.time) / 1000))
        return `file '${frame.name}'\nduration ${seconds.toFixed(6)}`
      })
      manifest.push(`file '${frames.at(-1).name}'`)
      const list = resolve(scratch, 'frames.txt')
      await writeFile(list, `${manifest.join('\n')}\n`)
      runBinary(ffmpeg, ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', list,
        '-vf', `fps=24,scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2,setsar=1`,
        '-c:v', 'libx264', '-preset', 'fast', '-crf', '22', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-an', video])
      return frames.length
    },
  }
}

function caption(page, text) {
  return page.evaluate((label) => {
    let element = document.getElementById('batch7-recording-caption')
    if (!element) {
      element = document.createElement('div')
      element.id = 'batch7-recording-caption'
      element.setAttribute('aria-hidden', 'true')
      Object.assign(element.style, {
        position: 'fixed', zIndex: '2147483647', left: '20px', bottom: '18px',
        color: 'white', background: '#1c3752', padding: '10px 16px', borderRadius: '8px',
        boxShadow: '0 2px 12px #0e24374d', font: '600 17px system-ui, sans-serif', pointerEvents: 'none',
      })
      document.body.append(element)
    }
    element.textContent = label
  }, text)
}

async function readData(page, path) {
  const payload = await page.evaluate(async (url) => {
    const response = await fetch(url, { headers: { token: localStorage.getItem('token') }, signal: AbortSignal.timeout(5000) })
    return response.json()
  }, path)
  assert.equal(payload.code, '200', `Fixture read failed for ${path}`)
  return payload.data
}

async function recordDemo() {
  assert.ok(values.origin, usage)
  const url = new URL(values.origin)
  assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) && ['http:', 'https:'].includes(url.protocol)
    && !url.username && !url.password && url.pathname === '/' && !url.search && !url.hash, 'Supply the isolated loopback origin')
  const origin = url.origin
  let state
  if (values['fixture-state']) {
    state = JSON.parse(await readFile(values['fixture-state'], 'utf8'))
    const fixtureOrigin = state.origin ?? state.baseUrl
    if (typeof fixtureOrigin === 'string') assert.equal(new URL(fixtureOrigin).origin, origin, 'Fixture origin differs from the supplied origin')
  }
  const health = await fetch(`${origin}/healthz`, { signal: AbortSignal.timeout(5000) })
  assert.equal((await health.text()).trim(), 'ok', 'The isolated Nginx entry point is unavailable')

  const requireFrontend = createRequire(new URL('../../mental-health-assistant-fronted/package.json', import.meta.url))
  const { chromium } = requireFrontend('playwright')
  const browserPath = process.env.BROWSER_EXECUTABLE_PATH || [
    'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  ].find(existsSync)
  const ffmpeg = process.env.FFMPEG_BIN || (existsSync('E:/ffmpeg/bin/ffmpeg.exe') ? 'E:/ffmpeg/bin/ffmpeg.exe' : 'ffmpeg')
  const ffprobe = process.env.FFPROBE_BIN || (existsSync('E:/ffmpeg/bin/ffprobe.exe') ? 'E:/ffmpeg/bin/ffprobe.exe' : 'ffprobe')
  runBinary(ffmpeg, ['-version'])
  runBinary(ffprobe, ['-version'])
  const target = resolve(repository, 'mental-health-assistant-backend/target')
  await mkdir(target, { recursive: true })
  const scratch = await mkdtemp(resolve(target, 'batch7-demo-'))
  const media = values.output ? resolve(values.output) : resolve(repository, 'docs/verification/batch7/media')
  const browser = await chromium.launch({ headless: true, ...(browserPath ? { executablePath: browserPath } : {}) })
  const context = await browser.newContext({ viewport: { width, height }, locale: 'zh-CN', deviceScaleFactor: 1 })
  const recorder = createScreencast(context, scratch)
  const page = await context.newPage()
  page.setDefaultTimeout(12_000)
  const calls = []
  context.on('request', (request) => {
    const url = new URL(request.url())
    if (url.origin === origin && request.method() === 'POST') calls.push(url.pathname)
  })
  const articleTitle = '蓝鹊阅览室预约指南'
  const articleText = '蓝鹊阅览室每周三19:10开放预约，现场凭蓝色号牌入场。'
  const firstFragment = '演练首段。'
  const screenshots = []
  const screenshot = async (page, name) => {
    await page.screenshot({ path: resolve(scratch, name) })
    screenshots.push(name)
  }

  try {
    await recorder.follow(page)
    await page.goto(`${origin}/auth/login`, { waitUntil: 'domcontentloaded' })
    await caption(page, '演示 1 / 3 · 登录隔离演练账户')
    // These credentials belong to the fictional Compose fixture.
    await page.getByPlaceholder('请输入用户名').fill('batch7_user')
    await page.getByPlaceholder('请输入密码').fill('FictionalBatch7!2026')
    await pause(900)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL(`${origin}/`)
    const articles = await readData(page, '/api/knowledge/article/page?currentPage=1&size=30&sortField=publishedAt&sortDirection=desc')
    const article = articles.records.find((item) => item.title === articleTitle)
    assert.ok(article?.id && article.coverImage?.startsWith('/upload/'), 'Publish the fictional article and its local cover before recording')
    if (state?.article) assert.equal(article.id, state.article, 'Published article differs from the exercise fixture')
    if (state?.upload) assert.equal(article.coverImage, state.upload, 'Published cover differs from the exercise fixture')
    const active = await readData(page, '/api/psychological-chat/stream/active')
    assert.ok(!active?.sessionId, 'The fictional account already has an active reply')

    await page.goto(`${origin}/consultation`, { waitUntil: 'domcontentloaded' })
    await caption(page, '演示 1 / 3 · 开始咨询')
    const input = page.getByPlaceholder('请输入您想要分享的内容...')
    await input.fill('演练展示：我想给自己安排一段安静的放松时间。')
    await pause(700)
    const sessionResponse = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/psychological-chat/session/start')
    await input.press('Enter')
    const created = await (await sessionResponse).json()
    const sessionId = String(created.data?.sessionId ?? created.data?.id).replace(/^session_/, '')
    assert.match(sessionId, /^\d+$/)
    await page.locator('.ai-message .markdown-content').getByText(firstFragment, { exact: true }).waitFor()
    await pause(850)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await caption(page, '演示 1 / 3 · 刷新后重连观看')
    const currentSession = page.locator('.session-item').filter({ has: page.locator('.generating-chip') })
    await currentSession.click({ timeout: 5000 })
    const replay = page.locator('.ai-message .markdown-content').getByText(firstFragment, { exact: true })
    await replay.waitFor({ timeout: 5000 })
    assert.equal(await replay.count(), 1)
    await screenshot(page, '01-consultation-reattach.png')
    await pause(700)
    await page.getByTitle('停止生成', { exact: true }).click({ timeout: 5000 })
    await page.waitForFunction(() => !document.querySelector('textarea')?.disabled)
    const messages = await readData(page, `/api/psychological-chat/sessions/${sessionId}/messages`)
    assert.deepEqual(messages.filter((message) => message.senderType === 2).map((message) => message.content), [firstFragment])
    await caption(page, '演示 1 / 3 · 已停止并保存回复片段')
    await pause(2200)

    await page.getByRole('link', { name: '知识问答', exact: true }).click()
    await caption(page, '演示 2 / 3 · 查资料并回答')
    await page.getByLabel('你的问题').fill('蓝鹊阅览室每周几几点开放预约？现场凭什么入场？')
    await pause(850)
    await page.getByRole('button', { name: '查找资料并回答', exact: true }).click()
    await page.getByRole('heading', { name: '资料回答', exact: true }).waitFor()
    const answer = await page.locator('.qa-answer-text').textContent()
    assert.match(answer, /周三\s*19:10/)
    assert.match(answer, /蓝色号牌/)
    const citation = page.getByRole('link', { name: new RegExp(articleTitle) })
    assert.equal(await citation.getAttribute('href'), `/knowledge/article/${article.id}`)
    await screenshot(page, '02-grounded-answer.png')
    await pause(2550)

    const newPage = page.waitForEvent('popup')
    await citation.click()
    const source = await newPage
    await recorder.follow(source)
    await source.getByRole('heading', { name: articleTitle, exact: true }).waitFor()
    await caption(source, '演示 2 / 3 · 打开引用原文')
    await source.getByText(articleText, { exact: true }).scrollIntoViewIfNeeded()
    await screenshot(source, '03-source-article.png')
    await pause(2600)

    await source.getByRole('link', { name: '知识库', exact: true }).click()
    await source.getByRole('heading', { name: '知识库', level: 1, exact: true }).waitFor()
    await caption(source, '演示 3 / 3 · 同源封面加载完成')
    const cover = source.locator('.article-item').filter({ hasText: articleTitle }).locator('img')
    await cover.waitFor()
    await source.waitForFunction((path) => Array.from(document.images).some((image) =>
      new URL(image.src).pathname === path && image.complete && image.naturalWidth > 0), article.coverImage)
    const coverUrl = new URL(await cover.evaluate((image) => image.src))
    assert.equal(coverUrl.origin, origin)
    assert.equal(coverUrl.pathname, article.coverImage)
    const coverResponse = await fetch(coverUrl, { signal: AbortSignal.timeout(5000) })
    assert.ok(coverResponse.ok, 'Unable to read the loaded cover through the proxy')
    const coverSha256 = createHash('sha256').update(Buffer.from(await coverResponse.arrayBuffer())).digest('hex')
    if (state?.uploadSha256) assert.equal(coverSha256, state.uploadSha256)
    await cover.scrollIntoViewIfNeeded()
    await screenshot(source, '04-same-origin-cover.png')
    await pause(2200)

    const expected = ['/api/psychological-chat/stream', `/api/psychological-chat/stream/attach/${sessionId}`,
      '/api/psychological-chat/stream/stop', '/api/knowledge/qa']
    for (const path of expected) assert.equal(calls.filter((call) => call === path).length, 1, `Unexpected request count for ${path}`)
    const videoName = 'batch7-deployment-demo.mp4'
    const video = resolve(scratch, videoName)
    const frameCount = await recorder.encode(ffmpeg, video)
    const probe = JSON.parse(runBinary(ffprobe, ['-v', 'error', '-show_entries', 'stream=codec_name,codec_type,width,height:format=duration,size', '-of', 'json', video]))
    assert.deepEqual(probe.streams, [{ codec_name: 'h264', codec_type: 'video', width, height }])
    assert.ok(Number(probe.format.duration) >= 10 && Number(probe.format.duration) <= 75, 'Recording duration is outside the demo range')
    const report = { origin, browser: browser.version(), articleId: article.id, sessionId, cover: coverUrl.pathname, coverSha256,
      savedReply: firstFragment, requestCounts: Object.fromEntries(expected.map((path) => [path, 1])), frameCount,
      video: { file: videoName, seconds: Number(probe.format.duration), bytes: Number(probe.format.size), width, height }, screenshots }
    await mkdir(media, { recursive: true })
    for (const name of [videoName, ...screenshots]) await copyFile(resolve(scratch, name), resolve(media, name))
    await writeFile(resolve(media, 'recording.json'), `${JSON.stringify(report, null, 2)}\n`)
    console.log(`Recorded ${report.video.seconds}s of the isolated deployment to ${resolve(media, videoName)}`)
  } catch (error) {
    await page.screenshot({ path: resolve(scratch, 'failure.png') }).catch(() => {})
    console.error(`Capture workspace: ${scratch}`)
    throw error
  } finally {
    await recorder.stop().catch(() => {})
    await browser.close()
  }
  const verifiedScratch = await realpath(scratch)
  assert.equal(dirname(verifiedScratch), await realpath(target))
  await rm(verifiedScratch, { recursive: true, force: true })
}

if (values.help) {
  console.log(usage)
} else {
  await recordDemo().catch((error) => { console.error(error.message); process.exitCode = 1 })
}
