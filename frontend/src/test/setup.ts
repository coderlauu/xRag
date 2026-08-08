import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

/**
 * jsdom 30 有 `HTMLDialogElement`，但没有 `showModal()`/`close()`——顶层层、`::backdrop`
 * 和背景 inert 都需要真实布局，jsdom 不做布局。
 *
 * 这是**环境缺口，不是产品缺陷**：`components/Modal.tsx` 和 `pages/chat/Drawer.tsx` 用原生
 * `<dialog>` 正是为了拿到浏览器的焦点约束。补一个最小实现，让弹层在测试里也能真的打开、
 * 关闭并派发 `close` 事件；不要反过来把生产代码改成手搓遮罩。
 */
if (typeof HTMLDialogElement !== "undefined" && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.show = function show(this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement, returnValue?: string) {
    if (returnValue !== undefined) {
      this.returnValue = returnValue;
    }
    this.removeAttribute("open");
    this.dispatchEvent(new Event("close"));
  };
}

afterEach(() => {
  cleanup();
});
