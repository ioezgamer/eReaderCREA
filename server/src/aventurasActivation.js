// Activación de CREA Aventuras por código (ADR-058 de CREA OS, docs/PLAN_VERSION_GLOBAL.md de CREA-Aventuras).
//
// La app de Google Play es global y NO lleva llave ni PIN. El tutor con Claude solo se enciende en una
// tablet con un código que emite CREA. Flujo:
//
//   1. Alguien de CREA crea un código (con la sesión de Google del panel): etiqueta, tablets permitidas y
//      vencimiento. El código en claro se muestra UNA vez; aquí solo se guarda un identificador derivado.
//   2. La tablet manda el código y la huella de su ID de instalación a POST /v1/aventuras/activate.
//   3. El servidor devuelve un TOKEN DE INSTALACIÓN firmado (HMAC), atado a esa tablet y con vencimiento.
//      No se guarda en ningún sitio: se comprueba la firma y que el código siga activo. Revocar el código
//      invalida todos sus tokens a la vez.
//   4. La app usa el token en /tutor y /tutor/report. Tope diario propio por tablet y por código.
//
// Minimización: no se guarda el código en claro, ni la IP (solo contadores por huella para frenar abusos),
// ni nada de lo que envía el tutor. Del dispositivo solo la huella (SHA-256 de un UUID al azar).
import { createHash, createHmac, randomInt, timingSafeEqual } from "node:crypto";
import { z } from "zod";

/** Sin 0, O, 1, I ni L (se confunden al escribir). 31 símbolos, 12 de ellos = ~59 bits. */
export const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const CODE_LENGTH = 12;
export const TOKEN_PREFIX = "v1.";

const DAY_MS = 86_400_000;
const HEX64 = /^[a-f0-9]{64}$/;

/** "abcd efgh-jkmn" -> "ABCD-EFGH-JKMN"; null si no tiene 12 símbolos válidos. */
export function normalizeCode(raw) {
  const clean = String(raw ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (clean.length !== CODE_LENGTH || [...clean].some((c) => !CODE_ALPHABET.includes(c))) return null;
  return clean.match(/.{4}/g).join("-");
}

export function generateCode() {
  let code = "";
  for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return code.match(/.{4}/g).join("-");
}

const b64 = (buffer) => Buffer.from(buffer).toString("base64url");
const sha256 = (text) => createHash("sha256").update(text).digest("hex");

export const createCodeInput = z.strictObject({
  label: z.string().trim().min(1).max(60),
  maxDevices: z.number().int().min(1).max(500),
  days: z.number().int().min(1).max(730),
});

export function createActivation({ config, store, ServiceError, now = () => new Date(), log = console.warn }) {
  const secret = String(config.aventurasTokenSecret ?? "");
  const enabled = secret.length >= 32;
  const cache = new Map(); // id -> { doc, until }: el código se relee cada 30 s como mucho

  const mac = (scope, text) => createHmac("sha256", secret).update(`${scope}:${text}`).digest();
  const codeId = (code) => mac("code", code).toString("hex").slice(0, 16);
  const need = () => {
    if (!enabled) throw new ServiceError(503, "activation_disabled", "La activación por código no está disponible");
  };

  async function loadCode(id, { fresh = false } = {}) {
    const hit = cache.get(id);
    if (!fresh && hit && hit.until > now().getTime()) return hit.doc;
    const doc = await store.get(`aventuras_code/${id}`);
    cache.set(id, { doc, until: now().getTime() + 30_000 });
    if (cache.size > 200) cache.delete(cache.keys().next().value);
    return doc;
  }

  const usable = (doc) => doc && doc.revoked !== true && Number(doc.expiresAt) > now().getTime();

  /** Crea un código nuevo. Devuelve el código en claro (solo aquí) y su identificador. */
  async function createCode(rawInput, createdBy) {
    need();
    const parsed = createCodeInput.safeParse(rawInput);
    if (!parsed.success) throw new ServiceError(400, "invalid_input", "Datos no válidos");
    const { label, maxDevices, days } = parsed.data;
    const code = generateCode();
    const id = codeId(code);
    const doc = {
      id, label, maxDevices, createdAt: now().getTime(), expiresAt: now().getTime() + days * DAY_MS,
      revoked: false, createdBy: String(createdBy ?? "").slice(0, 120),
    };
    await store.set(`aventuras_code/${id}`, doc);
    // Índice para poder listar: un campo por código (incremento atómico, sin leer y reescribir el documento).
    await store.increment("aventuras_code_index", { [id]: 1 });
    log("Código de Aventuras creado:", JSON.stringify({ id, label, maxDevices, days }));
    return { code, id, expiresAt: doc.expiresAt };
  }

  async function listCodes() {
    need();
    const index = (await store.get("aventuras_code_index")) ?? {};
    const day = now().toISOString().slice(0, 10);
    const codes = [];
    for (const id of Object.keys(index)) {
      const doc = await store.get(`aventuras_code/${id}`);
      if (!doc) continue;
      const count = (await store.get(`aventuras_code_count/${id}`))?.devices ?? 0;
      const today = (await store.get(`aventuras_code_day/${day}/${id}`))?.total ?? 0;
      codes.push({
        id, label: doc.label, maxDevices: doc.maxDevices, devices: Math.min(count, doc.maxDevices),
        expiresAt: doc.expiresAt, revoked: doc.revoked === true, createdAt: doc.createdAt, queriesToday: today,
      });
    }
    return codes.sort((a, b) => b.createdAt - a.createdAt);
  }

  async function revoke(id) {
    need();
    if (!/^[a-f0-9]{16}$/.test(String(id))) throw new ServiceError(400, "invalid_input", "Identificador no válido");
    const doc = await store.get(`aventuras_code/${id}`);
    if (!doc) throw new ServiceError(404, "code_unknown", "Código desconocido");
    await store.set(`aventuras_code/${id}`, { ...doc, revoked: true });
    cache.delete(id);
    log("Código de Aventuras revocado:", JSON.stringify({ id, label: doc.label }));
    return { id, revoked: true };
  }

  /** Frena los intentos de adivinar códigos: por tablet, por red (huella de la IP) y en total, cada hora. */
  async function throttle(device, ipHash) {
    const hour = now().toISOString().slice(0, 13);
    const limits = [
      [`aventuras_activate_try/${hour}/d-${device}`, config.aventurasActivateDeviceHourly],
      [`aventuras_activate_try/${hour}/i-${ipHash}`, config.aventurasActivateIpHourly],
      [`aventuras_activate_try/${hour}/all`, config.aventurasActivateHourly],
    ];
    for (const [key, limit] of limits) {
      const { n } = await store.increment(key, { n: 1 });
      if (n > limit) throw new ServiceError(429, "rate_limited", "Demasiados intentos");
    }
  }

  /** POST /activate: valida el código y entrega el token de instalación. */
  async function activate({ code: rawCode, device }, ip = "") {
    need();
    if (!HEX64.test(String(device ?? ""))) throw new ServiceError(400, "invalid_input", "Datos no válidos");
    await throttle(device, sha256(String(ip)).slice(0, 32));
    const code = normalizeCode(rawCode);
    if (!code) throw new ServiceError(400, "code_format", "Formato de código no válido");
    const id = codeId(code);
    const doc = await loadCode(id, { fresh: true });
    // Mismo error para "no existe", "revocado" y "vencido": no se da pistas sobre qué códigos existen.
    if (!usable(doc)) throw new ServiceError(404, "code_unknown", "Código desconocido");

    const known = await store.get(`aventuras_code_device/${id}/${device}`);
    if (!known) {
      // Se RESERVA el cupo con un incremento atómico y se comprueba el total devuelto: dos tablets a la vez
      // no pueden quedarse con el último cupo. Una reserva rechazada no se devuelve (sin atomicidad no se puede).
      const { devices } = await store.increment(`aventuras_code_count/${id}`, { devices: 1 });
      if (devices > doc.maxDevices) throw new ServiceError(409, "code_exhausted", "El código ya se usó en todas las tablets permitidas");
      await store.set(`aventuras_code_device/${id}/${device}`, { at: now().getTime() });
    }
    const expiresAtMs = Math.min(now().getTime() + config.aventurasTokenDays * DAY_MS, Number(doc.expiresAt));
    const expiresAt = Math.floor(expiresAtMs / 1000);
    const payload = b64(JSON.stringify({ v: 1, c: id, d: device, e: expiresAt }));
    const token = `${TOKEN_PREFIX}${payload}.${b64(mac("token", payload))}`;
    return { token, expiresAt, label: doc.label };
  }

  /** Comprueba firma, vencimiento, que sea de esta tablet y que el código siga activo. null = no válido. */
  async function verifyToken(token, device) {
    if (!enabled || typeof token !== "string" || !token.startsWith(TOKEN_PREFIX) || !HEX64.test(String(device))) return null;
    const parts = token.slice(TOKEN_PREFIX.length).split(".");
    if (parts.length !== 2 || token.length > 400) return null;
    const [payload, signature] = parts;
    const expected = mac("token", payload);
    let given;
    try { given = Buffer.from(signature, "base64url"); } catch { return null; }
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) return null;
    let data;
    try { data = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")); } catch { return null; }
    if (data?.v !== 1 || data.d !== device || !/^[a-f0-9]{16}$/.test(String(data.c)) || !(Number(data.e) * 1000 > now().getTime())) return null;
    const doc = await loadCode(data.c);
    if (!usable(doc)) return null;
    return { id: data.c, label: doc.label };
  }

  /** Cuenta una consulta del código en el día; false si superó su tope diario. */
  async function countQuery(id) {
    const day = now().toISOString().slice(0, 10);
    const { total } = await store.increment(`aventuras_code_day/${day}/${id}`, { total: 1 });
    return total <= config.aventurasCodeDailyLimit;
  }

  return { enabled, createCode, listCodes, revoke, activate, verifyToken, countQuery };
}
