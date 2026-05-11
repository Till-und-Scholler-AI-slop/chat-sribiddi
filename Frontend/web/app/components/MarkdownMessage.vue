<script setup lang="ts">
import MarkdownRender from 'markstream-vue'

const props = withDefaults(defineProps<{
  text?: string
  streaming?: boolean
}>(), {
  text: '',
  streaming: false
})

const colorMode = useColorMode()

const content = computed(() => props.text || '')
const isDark = computed(() => colorMode.value === 'dark')
</script>

<template>
  <div class="markdown-body">
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
      html-policy="safe"
    />
  </div>
</template>
