import fs from "node:fs";
import { createHash, timingSafeEqual } from "node:crypto";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createAuthenticator } from "./auth.js";
import { createActivation } from "./aventurasActivation.js";
import { createReports } from "./aventurasReport.js";
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

let adminPage = null;

/**
 * El HTML del panel se lee una sola vez. Su política de seguridad (CSP) solo deja ejecutar el script
 * propio de la página (por su hash) y el de Google Identity Services, y solo conectarse a Google.
 */
function loadAdminPage() {
  if (adminPage) return adminPage;
  const html = fs.readFileSync(path.join(ADMIN_DIR, "index.html"));
  const hashes = [...html.toString("utf8").matchAll(/<script>([\s\S]*?)<\/script>/g)]
    .map((match) => `'sha256-${createHash("sha256").update(match[1], "utf8").digest("base64")}'`);
  const csp = [
    "default-src 'none'",
    `script-src ${hashes.join(" ")} https://accounts.google.com/gsi/client`,
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://accounts.google.com/gsi/style",
    "font-src https://fonts.gstatic.com",
    "img-src 'self' data: https:",
    "connect-src 'self' https://www.googleapis.com https://accounts.google.com/gsi/",
    "frame-src https://accounts.google.com/gsi/",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join("; ");
  adminPage = { html, csp };
  return adminPage;
}

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
    const page = loadAdminPage();
    response.writeHead(200, { ...ADMIN_HEADERS, "content-security-policy": page.csp, "content-type": "text/html; charset=utf-8" });
    return response.end(page.html);
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

export function createServer({ config, service, authenticate, voice, reports, activation }) {
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

  const readJson = async (request) => {
    try {
      return JSON.parse(await readBody(request));
    } catch (error) {
      if (error instanceof service.ServiceError) throw error;
      throw new service.ServiceError(400, "invalid_json", "JSON no válido");
    }
  };
  // Huella de la red de quien llama (Render pone la IP real en X-Forwarded-For): solo para frenar abusos.
  const clientIp = (request) => String(request.headers["x-forwarded-for"] || request.socket.remoteAddress || "").split(",")[0].trim();

  return http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://localhost");
      if (url.pathname === "/healthz") return send(response, 200, { ok: true });
      if (url.pathname === "/admin" || url.pathname.startsWith("/admin/")) return serveAdmin(url, response);

      // Activación por código: sin sesión ni llave (es de donde sale el token). Lleva freno de intentos.
      if (request.method === "POST" && url.pathname === "/v1/aventuras/activate") {
        if (!activation) return send(response, 503, { ok: false, code: "activation_disabled" });
        const body = await readJson(request);
        const result = await activation.activate({ code: body?.code, device: body?.device }, clientIp(request));
        return send(response, 200, { ok: true, result });
      }

      if (request.method === "POST" && (url.pathname === "/v1/aventuras/tutor" || url.pathname === "/v1/aventuras/tutor/report")) {
        // Tres credenciales para la misma ruta: (1) el token de instalación que da la activación por código
        // (la versión global de Google Play; atado a la tablet, revocable); (2) la llave de servicio (CREA OS
        // llama entre servidores) y (3) la llave antigua de la app, solo para el APK 1.12.0-directo ya
        // repartido (se retira con AVENTURAS_APP_TOKEN vacío).
        const bearer = String(request.headers.authorization || "").replace(/^Bearer /, "");
        const device = String(request.headers["x-device-id"] || "");
        let code = null;
        if (bearer.startsWith("v1.")) {
          if (!activation) return send(response, 503, { ok: false, code: "activation_disabled" });
          if (!/^[a-f0-9]{64}$/.test(device)) return send(response, 400, { ok: false, code: "invalid_device" });
          code = await activation.verifyToken(bearer, device);
          if (!code) return send(response, 401, { ok: false, code: "unauthorized" });
        }
        const supplied = Buffer.from(bearer);
        const matches = (expected) => {
          const a = Buffer.from(expected || "");
          return a.length > 0 && a.length === supplied.length && timingSafeEqual(a, supplied);
        };
        if (!code && !config.aventurasServiceToken && !config.aventurasAppToken) return send(response, 503, { ok: false, code: "tutor_disabled" });
        const direct = !code && !matches(config.aventurasServiceToken) && matches(config.aventurasAppToken);
        if (!code && !direct && !matches(config.aventurasServiceToken)) return send(response, 401, { ok: false, code: "unauthorized" });
        let body;
        try { body = JSON.parse(await readBody(request)); }
        catch (error) { if (error instanceof service.ServiceError) throw error; return send(response, 400, { ok: false, code: "invalid_json" }); }
        if (!/^[a-f0-9]{64}$/.test(device)) return send(response, 400, { ok: false, code: "invalid_device" });
        const deviceKey = (code ? "aventuras-code-" : direct ? "aventuras-app-" : "aventuras-") + device;

        // Reporte de una sugerencia de la IA (mismas llaves y mismo código de tablet que el tutor).
        if (url.pathname === "/v1/aventuras/tutor/report") {
          if (!reports) return send(response, 503, { ok: false, code: "reports_disabled" });
          const saved = await reports.submit(deviceKey, code || direct ? "app" : "service", body);
          return send(response, 200, { ok: true, ...saved });
        }

        // Tope diario del código (todas sus tablets): se cuenta antes de gastar una consulta.
        if (code && !(await activation.countQuery(code.id))) return send(response, 429, { ok: false, code: "daily_limit", message: "Límite diario del código alcanzado" });
        const identity = code ? `aventuras-code-${code.id}@creanicaragua.org` : direct ? "aventuras-app@creanicaragua.org" : "aventuras-service@creanicaragua.org";
        const { result, model } = await service.ask(
          { email: identity },
          deviceKey,
          "aventuras_tutor",
          body,
          { dailyLimit: code ? config.aventurasCodeDailyLimit : direct ? config.aventurasAppDailyLimit : config.aventurasDailyLimit },
        );
        return send(response, 200, { ok: true, result, model });
      }

      const user = await authenticate(request);
      if (!user) return send(response, 401, { ok: false, code: "unauthorized" });
      const deviceId = String(request.headers["x-device-id"] ?? "").slice(0, 80) || "sin-id";

      // Reportes de contenido del tutor de Aventuras, para revisarlos: con la sesión de Google de CREA.
      if (request.method === "GET" && url.pathname === "/v1/aventuras/tutor/reports") {
        if (!reports) return send(response, 503, { ok: false, code: "reports_disabled" });
        const day = url.searchParams.get("day") || new Date().toISOString().slice(0, 10);
        return send(response, 200, { ok: true, ...(await reports.list(day)) });
      }
      // Códigos de activación de Aventuras: los gestiona quien entra con la cuenta de CREA.
      if (url.pathname === "/v1/aventuras/codes" || url.pathname === "/v1/aventuras/codes/revoke") {
        if (!activation) return send(response, 503, { ok: false, code: "activation_disabled" });
        if (request.method === "GET" && url.pathname === "/v1/aventuras/codes") return send(response, 200, { ok: true, codes: await activation.listCodes() });
        if (request.method === "POST" && url.pathname === "/v1/aventuras/codes") return send(response, 200, { ok: true, ...(await activation.createCode(await readJson(request), user.email)) });
        if (request.method === "POST" && url.pathname === "/v1/aventuras/codes/revoke") return send(response, 200, { ok: true, ...(await activation.revoke((await readJson(request))?.id)) });
      }
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
        const audio = await voice.speak(deviceId, body.text, body.language, body.voice, body.previousText);
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
  const reports = createReports({ config, store, ServiceError: service.ServiceError });
  const activation = createActivation({ config, store, ServiceError: service.ServiceError });
  if (!activation.enabled) console.warn("Activación por código apagada: define AVENTURAS_TOKEN_SECRET (32 caracteres o más).");
  createServer({ config, service, authenticate, voice, reports, activation }).listen(port, () => {
    console.log(`CREA Reader AI escuchando en :${port} (mock=${config.mock}, auth=${config.authMode}, modelo=${config.models.fast}, contadores=${store.kind})`);
    if (config.authMode === "google" && config.allowedAudiences.length === 0) {
      console.warn("ATENCIÓN: ALLOWED_AUDIENCES está vacío; se acepta el token de cualquier aplicación de Google de la organización.");
    }
  });
}
