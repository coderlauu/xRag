import { Outlet, RouterProvider, createRootRoute, createRoute, createRouter } from "@tanstack/react-router";
import { AppShell } from "../shell/app-shell";
import { AskPage } from "../features/answers/pages/ask-page";
import { DetailPage } from "../features/detail/pages/detail-page";
import { InboxPage } from "../features/inbox/pages/inbox-page";
import { OpsPage } from "../features/ops/pages/ops-page";
import { SearchPage } from "../features/search/pages/search-page";

const rootRoute = createRootRoute({
  component: () => (
    <AppShell>
      <Outlet />
    </AppShell>
  )
});

const inboxRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  component: InboxPage
});

const searchRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/search",
  component: SearchPage
});

const askRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/ask",
  component: AskPage
});

const detailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/detail/$documentId",
  component: () => <DetailPage />
});

const opsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/ops",
  component: OpsPage
});

const routeTree = rootRoute.addChildren([inboxRoute, searchRoute, askRoute, detailRoute, opsRoute]);

const router = createRouter({
  routeTree
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

export function AppRouter() {
  return <RouterProvider router={router} />;
}
