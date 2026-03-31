import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Outlet, RouterProvider, createRootRoute, createRouter, createRoute } from "@tanstack/react-router";
import { AppShell } from "./shell/app-shell";

const queryClient = new QueryClient();

const rootRoute = createRootRoute({
  component: () => (
    <AppShell>
      <Outlet />
    </AppShell>
  )
});

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  component: () => (
    <div>
      <h2>Formal engineering scaffold is ready.</h2>
      <p>Next step is implementing Inbox, Search, and Detail flows on top of this app shell.</p>
    </div>
  )
});

const routeTree = rootRoute.addChildren([indexRoute]);

const router = createRouter({
  routeTree
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </React.StrictMode>
);
