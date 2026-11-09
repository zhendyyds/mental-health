import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { createSafeMarkdownRenderer, isSafeLink } from '../src/utils/safeMarkdown.js'

const window = new JSDOM('').window
const render = createSafeMarkdownRenderer(window)
const documentFor = (markdown) => new JSDOM(render(markdown)).window.document

test('normal Markdown keeps headings, emphasis, lists, code and safe links', () => {
  const doc = documentFor('# 标题\n\n**粗体** 和 *斜体*\n\n- 第一项\n- 第二项\n\n```js\nconst value = "<script>"\n```\n\n[外部](https://example.test/help) [站内](/knowledge/abc)')
  assert.equal(doc.querySelector('h1').textContent, '标题')
  assert.equal(doc.querySelector('strong').textContent, '粗体')
  assert.equal(doc.querySelector('em').textContent, '斜体')
  assert.equal(doc.querySelectorAll('li').length, 2)
  assert.match(doc.querySelector('pre code').textContent, /<script>/)
  assert.equal(doc.querySelectorAll('a').length, 2)
  for (const link of doc.querySelectorAll('a')) {
    assert.equal(link.getAttribute('target'), '_blank')
    assert.equal(link.getAttribute('rel'), 'noopener noreferrer')
  }
})

test('raw HTML and attribute injection never create active elements or handlers', () => {
  const doc = documentFor('<img src=x onerror="alert(1)">\n<svg onload=alert(2)></svg>\n<script>alert(3)</script>\n\n[注入](https://example.test/ "x\\\" onmouseover=\\\"alert(4)")')
  assert.equal(doc.querySelectorAll('script,img,svg,iframe,object').length, 0)
  for (const node of doc.querySelectorAll('*')) {
    for (const attribute of node.attributes) assert.ok(!/^on/i.test(attribute.name))
  }
  assert.match(doc.body.textContent, /<img/)
})

test('dangerous schemes, network paths and encoded variants are rejected', () => {
  const unsafe = ['javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'data:text/html,<script>alert(1)</script>', 'vbscript:msgbox(1)', '//evil.test', '\\evil.test', '/\\evil.test', '%2f%2fevil.test', '%252f%252fevil.test', 'java\tscript:alert(1)', '%6a%61vascript%3aalert(1)', 'mailto:x@example.test']
  for (const target of unsafe) {
    assert.equal(isSafeLink(target), false, target)
    const doc = documentFor(`[危险](${target})`)
    for (const link of doc.querySelectorAll('a[href]')) assert.equal(isSafeLink(link.getAttribute('href')), true)
  }
  const entityDoc = documentFor('[危险](jav&#x61;script:alert%281%29)')
  assert.equal(entityDoc.querySelector('a'), null)
})

test('safe relative paths, anchors and HTTP(S) URLs remain usable', () => {
  for (const target of ['/knowledge/abc', './article/abc', '../help', '#source-1', '?page=2', 'https://example.test/path', 'http://example.test']) assert.equal(isSafeLink(target), true, target)
})
