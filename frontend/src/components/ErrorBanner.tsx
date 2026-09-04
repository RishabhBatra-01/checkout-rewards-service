import { ApiError } from "@/lib/api";

/**
 * Shows the backend's own `detail` message. The backend already writes specific,
 * actionable text ("Product 5 has 3 in stock but 10 were requested"), so the client
 * does not invent competing wording for it.
 */
export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;

  const message =
    error instanceof ApiError
      ? error.detail
      : error instanceof Error
        ? error.message
        : "Something went wrong.";
  const code = error instanceof ApiError ? error.code : null;

  return (
    <div className="banner error" role="alert">
      {message}
      {code && code !== "NETWORK_ERROR" && <> <span className="code">({code})</span></>}
    </div>
  );
}
