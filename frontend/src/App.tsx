import { useEffect, useState } from "react";
import "./App.css";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

interface HealthResponse {
  status: string;
}

function App() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch(`${API_BASE_URL}/api/v1/health`)
      .then((response) => response.json())
      .then(setHealth)
      .catch(() => setError("Unable to reach the backend."));
  }, []);

  return (
    <main>
      <h1>App</h1>
      <p>
        Backend status:{" "}
        {error ? error : (health?.status ?? "checking...")}
      </p>
    </main>
  );
}

export default App;
