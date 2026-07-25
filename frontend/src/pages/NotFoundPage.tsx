import { Link } from "react-router";

export function NotFoundPage() {
  return (
    <section>
      <h1>页面不存在</h1>
      <p>
        <Link to="/knowledge-bases">返回知识库列表</Link>
      </p>
    </section>
  );
}
