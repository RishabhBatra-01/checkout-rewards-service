import type { NextConfig } from "next";

/**
 * The browser only ever talks to this Next.js origin. Requests to /api/* are
 * forwarded server-side to Spring Boot, which keeps the app same-origin and means
 * the backend needs no CORS configuration.
 */
const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${BACKEND_URL}/api/:path*` }];
  },
};

export default nextConfig;
