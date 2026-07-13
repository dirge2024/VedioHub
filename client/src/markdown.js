import { marked } from 'marked'

const ALLOWED_TAGS = new Set([
  'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'P', 'BR', 'HR', 'BLOCKQUOTE',
  'UL', 'OL', 'LI', 'STRONG', 'EM', 'DEL', 'CODE', 'PRE', 'A',
  'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD'
])

export function renderMarkdown(markdown) {
  if (!markdown) return ''

  let cleanText = markdown.replace(/<think>[\s\S]*?<\/think>/gi, '')
  if (cleanText.includes('</think>')) cleanText = cleanText.split('</think>').pop()
  if (!cleanText.trim()) cleanText = markdown

  const template = document.createElement('template')
  template.innerHTML = marked.parse(cleanText)
  template.content.querySelectorAll('*').forEach(sanitizeNode)
  return template.innerHTML
}

function sanitizeNode(node) {
  if (!ALLOWED_TAGS.has(node.tagName)) {
    node.replaceWith(document.createTextNode(node.textContent || ''))
    return
  }

  for (const attribute of [...node.attributes]) {
    const allowed = node.tagName === 'A'
      && (attribute.name === 'href' || attribute.name === 'title')
    if (!allowed) node.removeAttribute(attribute.name)
  }
  if (node.tagName !== 'A') return

  const href = node.getAttribute('href') || ''
  if (!/^(https?:|mailto:|\/|#)/i.test(href)) node.removeAttribute('href')
  node.setAttribute('rel', 'noopener noreferrer')
  node.setAttribute('target', '_blank')
}
