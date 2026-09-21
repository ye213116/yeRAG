import unocss from '@unocss/vite';

export function setupUnocss() {
  // Icon components are compiled by unplugin-icons. Keeping a second icon preset
  // makes UnoCSS interpret component tag names as utility classes and emit false warnings.
  return unocss();
}
