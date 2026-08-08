import { NavLink, Outlet } from "react-router";

/**
 * 应用外壳：顶部导航 + 路由出口。业务页面只渲染自己的内容，不各自画页头。
 */
export function AppLayout() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <span className="app-brand">xrag</span>
        <nav aria-label="主导航">
          <NavLink to="/chat">问答</NavLink>
          <NavLink to="/knowledge-bases">知识库</NavLink>
          <NavLink to="/intent-tree">意图树</NavLink>
          <NavLink to="/diagnostics">诊断</NavLink>
        </nav>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
