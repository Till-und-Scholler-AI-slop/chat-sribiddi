<script setup lang="ts">
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  text: string
}>()

function escapeAttr(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;')
}

function stripSingleTrailingNewline(str: string): string {
  // Remove at most one trailing newline (handles both "\n" and "\r\n") but preserve other whitespace
  return str.replace(/\r?\n$/, '')
}

const COPY_ICON = `<svg class="copy-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>`
const CHECK_ICON = `<svg class="check-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`

const md = useState('markdown-it-instance', () => {
  const instance = new MarkdownIt({
    linkify: true,
    breaks: true
  })

  const defaultFence = instance.renderer.rules.fence?.bind(instance.renderer)
    ?? ((tokens: any, idx: any, options: any, _env: any, self: any) => self.renderToken(tokens, idx, options))

  instance.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const rawCode = tokens[idx].content
    const code = stripSingleTrailingNewline(rawCode)
    const inner = defaultFence(tokens, idx, options, env, self)
    return `<div class="code-block-wrapper"><button class="copy-code-btn" title="Copy code" data-code="${escapeAttr(code)}">${COPY_ICON}${CHECK_ICON}</button>${inner}</div>`
  }

  return instance
})

const rendered = computed(() => md.value.render(props.text || ''))

function handleClick(event: MouseEvent) {
  const btn = (event.target as HTMLElement).closest<HTMLElement>('.copy-code-btn')
  if (!btn) return
  const code = btn.dataset.code ?? ''

  const showCopied = () => {
    btn.classList.add('copied')
    setTimeout(() => btn.classList.remove('copied'), 2000)
  }

  if (navigator.clipboard) {
    navigator.clipboard.writeText(code).then(showCopied).catch(() => {})
  } else {
    const ta = document.createElement('textarea')
    ta.value = code
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy'); showCopied() } catch (_) {}
    document.body.removeChild(ta)
  }
}
</script>

<template>
  <div class="markdown-body" v-html="rendered" @click="handleClick" />
</template>
