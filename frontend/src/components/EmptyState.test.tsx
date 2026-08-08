import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { setTestViewport } from "../test/viewport";
import { EmptyState } from "./EmptyState";

it("空态可复用且viewport harness能覆盖批准断点", () => {
  setTestViewport(375, 720);
  render(<EmptyState title="还没有对话" detail="发出第一个问题后会自动保存" />);

  expect(window.innerWidth).toBe(375);
  expect(screen.getByText("还没有对话")).toBeInTheDocument();
  expect(screen.getByText("发出第一个问题后会自动保存")).toBeInTheDocument();
});
