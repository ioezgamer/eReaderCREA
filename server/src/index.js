import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createAuthenticator } from "./auth.js";
import { createService, loadConfig } from "./service.js";
import { createStore } from "./store.js";
import { createVoice, loadVoiceConfig } from "./voice.js";

const MAX_BODY = 160 * 1024;
const ADMIN_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "public", "admin");
const ADMIN_HEADERS = {
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "x-frame-options": "DENY",
};

/** Panel web: archivos estáticos (sin sesión; los datos se leen desde Drive con la cuenta de quien entra). */
function serveAdmin(url, response) {
  if (url.pathname === "/admin") {
    response.writeHead(301, { location: "/admin/" });
    return response.end();
  }
  if (url.pathname === "/admin/config.json") {
    response.writeHead(200, { ...ADMIN_HEADERS, "content-type": "application/json; charset=utf-8" });
    return response.end(JSON.stringify({
      clientId: process.env.GOOGLE_CLIENT_ID || "",
      domain: (process.env.ALLOWED_DOMAINS || "creanicaragua.org").split(",")[0].trim(),
    }));
  }
  if (url.pathname === "/admin/") {
    response.writeHead(200, { ...ADMIN_HEADERS, "content-type": "text/html; charset=utf-8" });
    return response.end(fs.readFileSync(path.join(ADMIN_DIR, "index.html")));
  }
  const asset = /^\/admin\/assets\/([\w-]+\.png)$/.exec(url.pathname);
  if (asset) {
    try {
      const file = fs.readFileSync(path.join(ADMIN_DIR, "assets", asset[1]));
      response.writeHead(200, { ...ADMIN_HEADERS, "cache-control": "public, max-age=86400", "content-type": "image/png" });
      return response.end(file);
    } catch {
      // cae al 404
    }
  }
  response.writeHead(404, ADMIN_HEADERS);
  return response.end();
}

export function createServer({ config, service, authenticate, voice }) {
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
      if (url.pathname === "/admin" || url.pathname.startsWith("/admin/")) return serveAdmin(url, response);

      const user = await authenticate(request);
      if (!user) return send(response, 401, { ok: false, code: "unauthorized" });
      const deviceId = String(request.headers["x-device-id"] ?? "").slice(0, 80) || "sin-id";

      if (request.method === "GET" && url.pathname === "/v1/status") {
        const voiceStatus = voice ? await voice.status(deviceId) : { available: false, reason: "disabled" };
        return send(response, 200, { ok: true, ...(await service.status(user, deviceId)), voice: voiceStatus });
      }
      if (request.method === "POST" && url.pathname === "/v1/voice") {
        if (!voice) throw new service.ServiceError(503, "unavailable", "Voz natural no disponible", { reason: "disabled" });
        let body;
        try {
          body = JSON.parse(await readBody(request));
        } catch (error) {
          if (error instanceof service.ServiceError) throw error;
          throw new service.ServiceError(400, "invalid_json", "JSON no válido");
        }
        const audio = await voice.speak(deviceId, body.text, body.language);
        response.writeHead(200, { "content-type": "audio/mpeg", "content-length": audio.length, "cache-control": "no-store" });
        return response.end(audio);
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
  const voice = createVoice({ config: loadVoiceConfig(), store, ServiceError: service.ServiceError });
  const port = Number(process.env.PORT || 8787);
  createServer({ config, service, authenticate, voice }).listen(port, () => {
    console.log(`CREA Reader AI escuchando en :${port} (mock=${config.mock}, auth=${config.authMode}, modelo=${config.models.fast})`);
  });
}
