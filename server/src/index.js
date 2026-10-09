import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createAuthenticator } from "./auth.js";
import { createService, loadConfig } from "./service.js";
import { createStore } from "./store.js";

const MAX_BODY = 160 * 1024;

export function createServer({ config, service, authenticate }) {
  const send = (response, status, body) => {
    const text = JSON.stringify(body);
    response.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
    response.end(text);
  };

  const readBody = (request) =>
    new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      request.on("data", (chunk) => {
        size += chunk.length;
        if (size > MAX_BODY) {
          reject(new service.ServiceError(413, "too_large", "Solicitud demasiado grande"));
          request.destroy();
        } else chunks.push(chunk);
      });
      request.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
      request.on("error", reject);
    });

  return http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://localhost");
      if (url.pathname === "/healthz") return send(response, 200, { ok: true });

      const user = await authenticate(request);
      if (!user) return send(response, 401, { ok: false, code: "unauthorized" });
      const deviceId = String(request.headers["x-device-id"] ?? "").slice(0, 80) || "sin-id";

      if (request.method === "GET" && url.pathname === "/v1/status") {
        return send(response, 200, { ok: true, ...(await service.status(user, deviceId)) });
      }
      if (request.method === "POST" && url.pathname === "/v1/ask") {
        let body;
        try {
          body = JSON.parse(await readBody(request));
        } catch (error) {
          if (error instanceof service.ServiceError) throw error;
          throw new service.ServiceError(400, "invalid_json", "JSON no válido");
        }
        const { result, model } = await service.ask(user, deviceId, String(body.task ?? ""), body.input ?? {});
        return send(response, 200, { ok: true, result, model });
      }
      return send(response, 404, { ok: false, code: "not_found" });
    } catch (error) {
      if (error instanceof service.ServiceError) {
        return send(response, error.status, { ok: false, code: error.code, message: error.message, ...error.extra });
      }
      console.error("Error inesperado:", error);
      return send(response, 500, { ok: false, code: "internal" });
    }
  });
}

if (fileURLToPath(import.meta.url) === path.resolve(process.argv[1] ?? "")) {
  const config = loadConfig();
  const store = await createStore();
  const service = createService({ config, store });
  const authenticate = createAuthenticator({ config });
  const port = Number(process.env.PORT || 8787);
  createServer({ config, service, authenticate }).listen(port, () => {
    console.log(`CREA Reader AI escuchando en :${port} (mock=${config.mock}, auth=${config.authMode}, modelo=${config.models.fast})`);
  });
}
