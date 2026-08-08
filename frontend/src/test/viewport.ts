/** 为 1440/720/375px 布局测试提供统一且会触发 resize 的入口。 */
export function setTestViewport(width: number, height: number) {
  Object.defineProperties(window, {
    innerWidth: { configurable: true, value: width },
    innerHeight: { configurable: true, value: height },
  });
  window.dispatchEvent(new Event("resize"));
}
