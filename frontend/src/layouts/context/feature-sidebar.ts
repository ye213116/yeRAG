import { inject, markRaw, onActivated, onBeforeUnmount, onDeactivated, provide, shallowRef, watchEffect } from 'vue';
import type { Component, ComputedRef, InjectionKey, ShallowRef } from 'vue';

interface SidebarContent {
  owner: symbol;
  component: Component;
  props: Record<string, unknown>;
}
const sidebarKey: InjectionKey<ShallowRef<SidebarContent | null>> = Symbol('feature-sidebar');
export function provideFeatureSidebar() {
  const content = shallowRef<SidebarContent | null>(null);
  provide(sidebarKey, content);
  return content;
}
export function useFeatureSidebar(component: Component, props: ComputedRef<Record<string, unknown>>) {
  const content = inject(sidebarKey);
  const owner = Symbol('sidebar-owner');
  const active = shallowRef(true);
  watchEffect(() => {
    if (content && active.value) content.value = { owner, component: markRaw(component), props: props.value };
  });
  function clear() {
    active.value = false;
    if (content?.value?.owner === owner) content.value = null;
  }
  onActivated(() => {
    active.value = true;
  });
  onDeactivated(clear);
  onBeforeUnmount(clear);
}
