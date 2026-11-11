// Contadores de uso y estado del servicio (presupuesto mensual, límites diarios, pausas).
//
// Tienen que sobrevivir a los reinicios: si se guardan en memoria, cada reinicio (el plan gratuito de
// Render se duerme tras unos minutos sin tráfico) los pone a cero y los topes de gasto dejan de valer.
// Por eso hay dos almacenes persistentes y la memoria queda solo para pruebas locales:
//
// - Redis por HTTP (Upstash, gratuito y sin tarjeta): UPSTASH_REDIS_REST_URL y UPSTASH_REDIS_REST_TOKEN.
// - Firestore (Google Cloud Run): se elige solo en Cloud Run o con GOOGLE_APPLICATION_CREDENTIALS.
//
// STORE=redis | firestore | memory fuerza uno concreto.

export class MemoryStore {
  constructor() {
    this.data = new Map();
    this.kind = "memory";
    this.persistent = false;
  }

  async get(key) {
    return this.data.get(key) ?? null;
  }

  async set(key, value) {
    this.data.set(key, value);
  }

  /** Suma `fields` a los contadores del documento y devuelve el resultado. */
  async increment(key, fields) {
    const current = this.data.get(key) ?? {};
    const next = { ...current };
    for (const [name, amount] of Object.entries(fields)) next[name] = (next[name] ?? 0) + amount;
    this.data.set(key, next);
    return next;
  }
}

const DAY_SECONDS = 86_400;

/** Cuánto se guarda cada contador: los diarios unas semanas, los mensuales algo más de un año. */
export function ttlFor(key) {
  if (/^(use|user|voice_day|aventuras_report_use)\//.test(key)) return 40 * DAY_SECONDS;
  // Reportes de contenido del tutor de Aventuras: se conservan 90 días para revisarlos y luego se borran
  // solos. El índice del día (aventuras_report_day) dura lo mismo: sin él no se pueden encontrar.
  if (/^(aventuras_report|aventuras_report_day)\//.test(key)) return 90 * DAY_SECONDS;
  if (/^(spend|voice_month)\//.test(key)) return 400 * DAY_SECONDS;
  return null; // control y voice_control no caducan
}

function parseHash(flat) {
  if (!Array.isArray(flat) || flat.length === 0) return null;
  const result = {};
  for (let i = 0; i + 1 < flat.length; i += 2) {
    const raw = flat[i + 1];
    try {
      result[flat[i]] = JSON.parse(raw);
    } catch {
      result[flat[i]] = raw;
    }
  }
  return result;
}

/**
 * Redis a través de la API REST de Upstash (solo `fetch`, sin dependencias). Cada documento es un hash
 * cuyos campos guardan JSON; los contadores se suman con HINCRBY, de forma atómica entre instancias.
 */
export function createRedisStore({ url, token, fetchImpl = fetch }) {
  const base = String(url).replace(/\/+$/, "");

  async function transaction(commands) {
    const response = await fetchImpl(`${base}/multi-exec`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify(commands),
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error(`Redis respondió ${response.status}`);
    const results = await response.json();
    const failed = Array.isArray(results) ? results.find((item) => item?.error) : { error: "respuesta no válida" };
    if (failed) throw new Error(`Redis: ${failed.error}`);
    return results.map((item) => item.result);
  }

  return {
    kind: "redis",
    persistent: true,
    async get(key) {
      const [flat] = await transaction([["HGETALL", key]]);
      return parseHash(flat);
    },
    async set(key, value) {
      const pairs = Object.entries(value ?? {}).flatMap(([name, field]) => [name, JSON.stringify(field)]);
      const commands = [["DEL", key]];
      if (pairs.length > 0) commands.push(["HSET", key, ...pairs]);
      // Los documentos con caducidad (ttlFor) también la llevan cuando se escriben enteros.
      const ttl = ttlFor(key);
      if (ttl && pairs.length > 0) commands.push(["EXPIRE", key, String(ttl)]);
      await transaction(commands);
    },
    async increment(key, fields) {
      const commands = Object.entries(fields).map(([name, amount]) =>
        Number.isInteger(amount) ? ["HINCRBY", key, name, String(amount)] : ["HINCRBYFLOAT", key, name, String(amount)],
      );
      const ttl = ttlFor(key);
      if (ttl) commands.push(["EXPIRE", key, String(ttl)]);
      commands.push(["HGETALL", key]);
      const results = await transaction(commands);
      return parseHash(results.at(-1)) ?? {};
    },
  };
}

async function createFirestoreStore(env) {
  const { Firestore, FieldValue } = await import("@google-cloud/firestore");
  const db = new Firestore();
  const collection = env.FIRESTORE_COLLECTION || "crea_reader_ai";
  const doc = (key) => db.collection(collection).doc(key.replaceAll("/", "_"));
  return {
    kind: "firestore",
    persistent: true,
    async get(key) {
      const snapshot = await doc(key).get();
      return snapshot.exists ? snapshot.data() : null;
    },
    async set(key, value) {
      await doc(key).set(value);
    },
    async increment(key, fields) {
      const updates = {};
      for (const [name, amount] of Object.entries(fields)) updates[name] = FieldValue.increment(amount);
      await doc(key).set(updates, { merge: true });
      return (await doc(key).get()).data();
    },
  };
}

const MEMORY_WARNING =
  "ATENCIÓN: los contadores se guardan en memoria y se pierden en cada reinicio; " +
  "MONTHLY_BUDGET_USD, USER_DAILY_LIMIT y VOICE_MONTHLY_CHARS no son topes reales. " +
  "Configura UPSTASH_REDIS_REST_URL y UPSTASH_REDIS_REST_TOKEN (ver server/README.md).";

export async function createStore(env = process.env, { fetchImpl = fetch, log = console } = {}) {
  const mode = String(env.STORE ?? "").trim().toLowerCase();
  const redis = env.UPSTASH_REDIS_REST_URL && env.UPSTASH_REDIS_REST_TOKEN
    ? { url: env.UPSTASH_REDIS_REST_URL.trim(), token: env.UPSTASH_REDIS_REST_TOKEN.trim() }
    : null;

  if (mode === "redis" || (!mode && redis)) {
    if (!redis) throw new Error("STORE=redis necesita UPSTASH_REDIS_REST_URL y UPSTASH_REDIS_REST_TOKEN");
    return createRedisStore({ ...redis, fetchImpl });
  }
  if (mode === "firestore" || (!mode && (env.K_SERVICE || env.GOOGLE_APPLICATION_CREDENTIALS))) {
    try {
      return await createFirestoreStore(env);
    } catch (error) {
      log.warn(`Firestore no disponible (${error.message}).`);
    }
  }
  log.warn(MEMORY_WARNING);
  return new MemoryStore();
}
