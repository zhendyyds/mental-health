import MarkdownIt from 'markdown-it'
import createDOMPurify from 'dompurify'

// Decode before checking, including nested percent encoding. Backslashes and
// control characters have browser-specific URL semantics, so reject them.
export const isSafeLink = (value) => {
  if (typeof value !== 'string' || !value.trim()) return false
  let normalized = value.trim()
  try {
    for (let i = 0; i < 4; i++) {
      const decoded = decodeURIComponent(normalized)
      if (decoded === normalized) break
      normalized = decoded
    }
    if (/[\u0000-\u0020\u007f\\]/.test(normalized) || normalized.startsWith('//')) return false
    const scheme = normalized.match(/^([a-z][a-z\d+.-]*):/i)
    const parsed = new URL(normalized, 'https://local.invalid')
    if (scheme) return /^https?:$/.test(parsed.protocol) && Boolean(parsed.hostname)
    return parsed.origin === 'https://local.invalid'
  } catch {
    return false
  }
}

export const createSafeMarkdownRenderer = (browserWindow) => {
  const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false })
  markdown.disable('image')
  markdown.validateLink = isSafeLink
  const renderLink = markdown.renderer.rules.link_open || ((tokens, index, options, env, renderer) => renderer.renderToken(tokens, index, options))
  markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
    tokens[index].attrSet('target', '_blank')
    tokens[index].attrSet('rel', 'noopener noreferrer')
    return renderLink(tokens, index, options, env, renderer)
  }
  const purifier = createDOMPurify(browserWindow)
  purifier.addHook('uponSanitizeAttribute', (_node, attribute) => {
    if (attribute.attrName === 'href' && !isSafeLink(attribute.attrValue)) attribute.keepAttr = false
  })
  return (content) => purifier.sanitize(markdown.render(String(content ?? '')), {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 's', 'blockquote', 'ul', 'ol', 'li', 'pre', 'code', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td'],
    ALLOWED_ATTR: ['href', 'title', 'class', 'target', 'rel'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false
  })
}

let browserRenderer
export const renderSafeMarkdown = (content) => {
  browserRenderer ||= createSafeMarkdownRenderer(window)
  return browserRenderer(content)
}
