<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { mixColor } from '@sa/color';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);

const bgColor = computed(() => {
  const ratio = themeStore.darkMode ? 0.9 : 0;

  return mixColor('#fff', '#000', ratio);
});
</script>

<template>
  <div class="relative size-full flex-center overflow-hidden" :style="{ backgroundColor: bgColor }">
    <div class="login-orb login-orb-a" />
    <div class="login-orb login-orb-b" />
    <div class="login-orb login-orb-c" />
    <NCard :bordered="false" class="login-card relative z-4 w-auto card-wrapper">
      <div class="w-400px lt-sm:w-300px">
        <header class="flex-y-center justify-between">
          <SystemLogo class="text-64px text-primary lt-sm:text-48px" />
          <h3 class="text-28px text-primary font-500 lt-sm:text-22px">{{ $t('system.title') }}</h3>
          <div class="i-flex-col">
            <ThemeSchemaSwitch
              :theme-schema="themeStore.themeScheme"
              :show-tooltip="false"
              class="text-20px lt-sm:text-18px"
              @switch="themeStore.toggleThemeScheme"
            />
            <LangSwitch
              v-if="themeStore.header.multilingual.visible"
              :lang="appStore.locale"
              :lang-options="appStore.localeOptions"
              :show-tooltip="false"
              @change-lang="appStore.changeLocale"
            />
          </div>
        </header>
        <main class="pt-24px">
          <h3 class="text-18px text-primary font-medium">{{ $t(activeModule.label) }}</h3>
          <div class="pt-24px">
            <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
              <component :is="activeModule.component" />
            </Transition>
          </div>
        </main>
      </div>
    </NCard>
  </div>
</template>

<style scoped>
.login-orb {
  position: absolute;
  border-radius: 9999px;
  filter: blur(90px);
  pointer-events: none;
}

.login-orb-a {
  top: -160px;
  left: -120px;
  width: 480px;
  height: 480px;
  background: radial-gradient(circle, rgb(var(--primary-color) / 0.35), transparent 65%);
}

.login-orb-b {
  right: -100px;
  bottom: -140px;
  width: 420px;
  height: 420px;
  background: radial-gradient(circle, rgb(8 145 178 / 0.3), transparent 65%);
}

.login-orb-c {
  top: 35%;
  right: 18%;
  width: 300px;
  height: 300px;
  background: radial-gradient(circle, rgb(163 230 53 / 0.22), transparent 65%);
}

.login-card {
  border-radius: 20px;
  box-shadow: 0 24px 64px rgb(0 0 0 / 0.1);
}

html.dark .login-card {
  box-shadow: 0 24px 64px rgb(0 0 0 / 0.45);
}

html.dark .login-orb {
  opacity: 0.55;
}
</style>
