import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AppLayout } from "./components/AppLayout";
import { ChunksPage } from "./pages/ChunksPage";
import { DiagnosticsPage } from "./pages/DiagnosticsPage";
import { DocumentsPage } from "./pages/DocumentsPage";
import { KnowledgeBasesPage } from "./pages/KnowledgeBasesPage";
import { NotFoundPage } from "./pages/NotFoundPage";

/**
 * 路由表。路径结构对应 tech/knowledge-base/ui-spec.md §1：
 *
 * ```
 * /knowledge-bases                                        知识库列表
 *   └─ /knowledge-bases/{kbId}                            文档列表
 *        └─ /knowledge-bases/{kbId}/documents/{docId}/chunks   分块管理
 * ```
 *
 * 分块页由工单 13 加入。文档详情不占路由——原型把它做成了文档列表上的右侧抽屉，
 * 这样能保留列表上下文。
 */
export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to="/knowledge-bases" replace />} />
          <Route path="/knowledge-bases" element={<KnowledgeBasesPage />} />
          <Route path="/knowledge-bases/:kbId" element={<DocumentsPage />} />
          <Route
            path="/knowledge-bases/:kbId/documents/:docId/chunks"
            element={<ChunksPage />}
          />
          <Route path="/diagnostics" element={<DiagnosticsPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
