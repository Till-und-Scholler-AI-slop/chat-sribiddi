<script setup lang="ts">
import MarkdownRender, { MarkdownCodeBlockNode, setCustomComponents } from 'markstream-vue'

setCustomComponents('chat', {
  code_block: MarkdownCodeBlockNode
})

const props = withDefaults(defineProps<{
  text?: string
  streaming?: boolean
}>(), {
  text: '',
  streaming: false
})

const colorMode = useColorMode()

const lightCodeTheme = 'github-light'
const darkCodeTheme = 'github-dark-dimmed'
const codeThemes = [lightCodeTheme, darkCodeTheme]
const codeBlockProps = {
  lightTheme: lightCodeTheme,
  darkTheme: darkCodeTheme,
  themes: codeThemes,
  maxWidth: '100%',
  showFontSizeButtons: false
}

const content = computed(() => props.text || '')
const isDark = computed(() => colorMode.value === 'dark')
</script>

<template>
  <div class="markdown-body prose max-w-none dark:prose-invert">
    <MarkdownRender
      custom-id="chat"
      :content="content"
      :final="!streaming"
      :typewriter="streaming"
      :fade="false"
      :is-dark="isDark"
      :max-live-nodes="streaming ? 0 : 320"
      :batch-rendering="true"
      :render-batch-size="16"
      :render-batch-delay="8"
      :render-batch-budget-ms="4"
      :code-block-dark-theme="darkCodeTheme"
      :code-block-light-theme="lightCodeTheme"
      :code-block-props="codeBlockProps"
      :themes="codeThemes"
      html-policy="safe"
    />
  </div>
</template>
