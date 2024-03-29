<script setup lang="ts">
import ToggleIcon from '@/components/ToggleIcon.vue'

const props = defineProps<{
  isOutputContextEnabledGlobally: boolean
  isOutputContextOverridden: boolean
  isDocsVisible: boolean
  isVisualizationVisible: boolean
}>()
const emit = defineEmits<{
  'update:isOutputContextOverridden': [isOutputContextOverridden: boolean]
  'update:isDocsVisible': [isDocsVisible: boolean]
  'update:isVisualizationVisible': [isVisualizationVisible: boolean]
}>()
</script>

<template>
  <div class="CircularMenu">
    <ToggleIcon
      :icon="props.isOutputContextEnabledGlobally ? 'no_auto_replay' : 'auto_replay'"
      class="icon-container button override-output-context-button"
      :class="{ 'output-context-overridden': props.isOutputContextOverridden }"
      :alt="`${
        props.isOutputContextEnabledGlobally != props.isOutputContextOverridden
          ? 'Disable'
          : 'Enable'
      } output context`"
      :modelValue="props.isOutputContextOverridden"
      @update:modelValue="emit('update:isOutputContextOverridden', $event)"
    />
    <ToggleIcon
      icon="docs"
      class="icon-container button docs-button"
      :alt="`${props.isDocsVisible ? 'Hide' : 'Show'} documentation`"
      :modelValue="props.isDocsVisible"
      @update:modelValue="emit('update:isDocsVisible', $event)"
    />
    <ToggleIcon
      icon="eye"
      class="icon-container button visualization-button"
      :alt="`${props.isVisualizationVisible ? 'Hide' : 'Show'} visualization`"
      :modelValue="props.isVisualizationVisible"
      @update:modelValue="emit('update:isVisualizationVisible', $event)"
    />
  </div>
</template>

<style scoped>
.CircularMenu {
  user-select: none;
  position: absolute;
  left: -36px;
  width: 76px;
  height: 76px;

  &:before {
    content: '';
    position: absolute;
    clip-path: path('m0 16a52 52 0 0 0 52 52a16 16 0 0 0 0 -32a20 20 0 0 1-20-20a16 16 0 0 0-32 0');
    backdrop-filter: var(--blur-app-bg);
    background: var(--color-app-bg);
    width: 100%;
    height: 100%;
  }
}

.icon-container {
  display: inline-flex;
  background: none;
  padding: 0;
  border: none;
  opacity: 30%;
}

.toggledOn {
  opacity: unset;
}

.override-output-context-button {
  position: absolute;
  left: 9px;
  top: 8px;
}

.output-context-overridden {
  opacity: 100%;
  color: red;
}

.docs-button {
  position: absolute;
  left: 18.54px;
  top: 33.46px;
}

.visualization-button {
  position: absolute;
  left: 44px;
  top: 44px;
}
</style>
