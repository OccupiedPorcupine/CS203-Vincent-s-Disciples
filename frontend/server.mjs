import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { createServer, request as forward } from "node:http";
import { extname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL(".", import.meta.url)));
const port = Number(process.env.FRONTEND_PORT || 5500);
const host = "127.0.0.1";
// Requests to /api/* are passed to the backend, so the pages and the API share one address. That
// keeps the login cookie same-site when the app is opened from another device, e.g. an iPhone
// through Tailscale Serve (see frontend/README.md).
const backend = new URL(process.env.BACKEND_URL || "http://localhost:8080");

const contentTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".ico": "image/x-icon",
  ".jpeg": "image/jpeg",
  ".jpg": "image/jpeg",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml"
};

function proxyToBackend(request, response) {
  const headers = { ...request.headers, host: backend.host };
  // The browser sees one address, but the backend would see the page's Origin as a different
  // site and refuse it as a cross-origin request.
  delete headers.origin;
  const upstream = forward(
    { protocol: backend.protocol, hostname: backend.hostname, port: backend.port, method: request.method, path: request.url, headers },
    backendResponse => {
      response.writeHead(backendResponse.statusCode || 502, backendResponse.headers);
      backendResponse.pipe(response);
    }
  );
  upstream.on("error", () => {
    if (!response.headersSent) response.writeHead(502, { "Content-Type": "application/json; charset=utf-8" });
    response.end(JSON.stringify({ code: "BACKEND_UNAVAILABLE", message: "The backend isn't running." }));
  });
  request.pipe(upstream);
}

createServer(async (request, response) => {
  if (request.url?.startsWith("/api/")) {
    proxyToBackend(request, response);
    return;
  }

  if (!request.url || !["GET", "HEAD"].includes(request.method || "")) {
    response.writeHead(405, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Method not allowed");
    return;
  }

  try {
    const pathname = decodeURIComponent(new URL(request.url, `http://${host}`).pathname);
    const requestedPath = pathname === "/" ? "/index.html" : pathname;
    const filePath = resolve(root, `.${requestedPath}`);

    // Only serve files located inside the frontend directory.
    if (filePath !== root && !filePath.startsWith(`${root}${sep}`)) {
      response.writeHead(403, { "Content-Type": "text/plain; charset=utf-8" });
      response.end("Forbidden");
      return;
    }

    const fileStat = await stat(filePath);
    if (!fileStat.isFile()) throw new Error("Not a file");

    response.writeHead(200, {
      "Content-Type": contentTypes[extname(filePath).toLowerCase()] || "application/octet-stream",
      "Content-Length": fileStat.size,
      "Cache-Control": "no-cache"
    });
    if (request.method === "HEAD") {
      response.end();
    } else {
      createReadStream(filePath).pipe(response);
    }
  } catch {
    response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Not found");
  }
}).listen(port, host, () => {
  console.log(`Chicky frontend: http://localhost:${port} (API: ${backend.origin})`);
  console.log("Press Ctrl+C to stop.");
});
