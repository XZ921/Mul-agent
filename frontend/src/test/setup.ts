import '@testing-library/jest-dom/vitest'

// 为 Ant Design 的响应式与浮层组件补齐测试环境依赖，避免 jsdom 缺失浏览器能力导致组件在挂载阶段报错。
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})

if (!window.scrollTo) {
  window.scrollTo = () => {}
}

const nativeGetComputedStyle = window.getComputedStyle.bind(window)

Object.defineProperty(window, 'getComputedStyle', {
  writable: true,
  value: (element: Element, pseudoElt?: string) => {
    if (pseudoElt) {
      return nativeGetComputedStyle(element)
    }
    return nativeGetComputedStyle(element)
  },
})
