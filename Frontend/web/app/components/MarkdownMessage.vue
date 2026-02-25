<script setup lang="ts">
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  text: string
}>()

const md = useState('markdown-it-instance', () =>
  new MarkdownIt({
    linkify: true,
    breaks: true
  })
)

const rendered = computed(() => md.value.render(props.text || ''))
</script>

<template>
  <div class="markdown-body" v-html="rendered" />
</template>
