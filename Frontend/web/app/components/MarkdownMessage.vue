<script setup lang="ts">
import type MarkdownIt from 'markdown-it'
import MarkdownItLib from 'markdown-it'

const props = defineProps<{
  text: string
}>()

const COPY_ICON = `<svg class="copy-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>`
const CHECK_ICON = `<svg class="check-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`

const md = useState('markdown-it-instance', () => {
  const instance = new MarkdownItLib({
    linkify: true,
    breaks: true
  })

  const defaultFence: MarkdownIt.Renderer.RenderRule =
    instance.renderer.rules.fence?.bind(instance.renderer)
    ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

  instance.renderer.rules.fence = (
    tokens: MarkdownIt.Token[],
    idx: number,
    options: MarkdownIt.Options,
    env: unknown,
    self: MarkdownIt.Renderer
  ) => {
    const inner = defaultFence(tokens, idx, options, env, self)
    return `<div class="code-block-wrapper"><button class="copy-code-btn" type="button" aria-label="Copy code to clipboard" title="Copy code">${COPY_ICON}${CHECK_ICON}</button>${inner}</div>`
  }

  return instance
})

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function renderMarkdown(text: string): string {
  try {
    return md.value.render(text)
  } catch {
    return `<pre style="white-space:pre-wrap">${escapeHtml(text)}</pre>`
  }
}

// Throttle rendering to at most once per animation frame to prevent
// main-thread blocking during streaming (especially with linkify + many URLs
// in web-search responses where back-to-back md.render() calls freeze the page).
const rendered = ref(renderMarkdown(props.text || ''))
let rafId: number | undefined
let lastRenderedText: string = props.text || ''

watch(() => props.text, () => {
  const text = props.text || ''
  if (text === lastRenderedText) return
  if (import.meta.server) {
    lastRenderedText = text
    rendered.value = renderMarkdown(text)
    return
  }
  if (rafId == null) {
    rafId = requestAnimationFrame(() => {
      rafId = undefined
      const current = props.text || ''
      if (current === lastRenderedText) return
      lastRenderedText = current
      rendered.value = renderMarkdown(current)
    })
  }
})

onBeforeUnmount(() => {
  if (rafId != null) {
    cancelAnimationFrame(rafId)
    rafId = undefined
  }
})

const copyTimers = new WeakMap<HTMLElement, ReturnType<typeof setTimeout>>()

function showCopied(btn: HTMLElement) {
  const prev = copyTimers.get(btn)
  if (prev !== undefined) clearTimeout(prev)
  btn.classList.add('copied')
  copyTimers.set(btn, setTimeout(() => {
    btn.classList.remove('copied')
    copyTimers.delete(btn)
  }, 2000))
}

function getCodeText(btn: HTMLElement): string {
  const pre = btn.nextElementSibling
  return pre?.querySelector('code')?.textContent?.replace(/\r?\n$/, '') ?? ''
}

function copyWithExecCommand(btn: HTMLElement, code: string) {
  const ta = document.createElement('textarea')
  ta.value = code
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try {
    const ok = document.execCommand('copy')
    if (ok) showCopied(btn)
  } catch (_) {
    // ignore failures in legacy copy fallback
  } finally {
    document.body.removeChild(ta)
  }
}

function handleClick(event: MouseEvent) {
  const btn = (event.target as HTMLElement).closest<HTMLElement>('.copy-code-btn')
  if (!btn) return
  const code = getCodeText(btn)

  const canUseNavigatorClipboard =
    typeof navigator !== 'undefined' &&
    typeof navigator.clipboard !== 'undefined' &&
    typeof navigator.clipboard.writeText === 'function' &&
    typeof isSecureContext !== 'undefined' &&
    isSecureContext

  if (canUseNavigatorClipboard) {
    navigator.clipboard.writeText(code).then(() => showCopied(btn)).catch(() => {
      copyWithExecCommand(btn, code)
    })
  } else {
    copyWithExecCommand(btn, code)
  }
}
</script>

<template>
  <div class="markdown-body" @click="handleClick" v-html="rendered" />
</template>
