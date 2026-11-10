import assert from "node:assert/strict";
import { test } from "node:test";
import { MemoryStore, createRedisStore, createStore, ttlFor } from "../src/store.js";

/** Un Redis de mentira que entiende la API REST de Upstash (/multi-exec) lo justo para estas pruebas. */
function fakeUpstash() {
  const hashes = new Map();
  const expires = new Map();
  const requests = [];
  const run = ([command, key, ...args]) => {
    const hash = hashes.get(key) ?? new Map();
    switch (command) {
      case "HGETALL":
        return [...hash.entries()].flat();
      case "DEL":
        return hashes.delete(key) ? 1 : 0;
      case "HSET":
        for (let i = 0; i < args.length; i += 2) hash.set(args[i], args[i + 1]);
        hashes.set(key, hash);
        return args.length / 2;
      case "HINCRBY":
      case "HINCRBYFLOAT": {
        const next = Number(hash.get(args[0]) ?? 0) + Number(args[1]);
        hash.set(args[0], String(next));
        hashes.set(key, hash);
        return next;
      }
      case "EXPIRE":
        expires.set(key, Number(args[0]));
        return 1;
      default:
        return { error: `ERR unknown command ${command}` };
    }
  };
  const fetchImpl = async (url, options) => {
    requests.push({ url, options });
    assert.equal(options.headers.authorization, "Bearer secreto");
    const commands = JSON.parse(options.body);
    const results = commands.map((command) => {
      const value = run(command);
      return value?.error ? value : { result: value };
    });
    return { ok: true, status: 200, json: async () => results };
  };
  return { fetchImpl, requests, expires, hashes };
}

test("Redis: suma contadores de forma atómica, los devuelve y les pone caducidad", async () => {
  const redis = fakeUpstash();
  const store = createRedisStore({ url: "https://ejemplo.upstash.io/", token: "secreto", fetchImpl: redis.fetchImpl });
  assert.equal(await store.get("spend/2026-11"), null);
  await store.increment("spend/2026-11", { micros: 1500, calls: 1 });
  const total = await store.increment("spend/2026-11", { micros: 500, calls: 1 });
  assert.deepEqual(total, { micros: 2000, calls: 2 });
  assert.deepEqual(await store.get("spend/2026-11"), { micros: 2000, calls: 2 });
  assert.equal(redis.expires.get("spend/2026-11"), 400 * 86_400);
  assert.equal(redis.requests[0].url, "https://ejemplo.upstash.io/multi-exec");
  assert.equal(store.persistent, true);
});

test("Redis: set reemplaza el documento entero y conserva los tipos", async () => {
  const redis = fakeUpstash();
  const store = createRedisStore({ url: "https://x", token: "secreto", fetchImpl: redis.fetchImpl });
  await store.set("control", { enabled: false, suspendedUntil: 123, reason: "no_credits" });
  await store.set("control", { suspendedUntil: 456 });
  assert.deepEqual(await store.get("control"), { suspendedUntil: 456 });
  assert.equal(redis.expires.has("control"), false);
});

test("Redis: un error de Redis se convierte en excepción (no en contadores a cero)", async () => {
  const store = createRedisStore({
    url: "https://x",
    token: "secreto",
    fetchImpl: async () => ({ ok: true, status: 200, json: async () => [{ error: "WRONGTYPE" }] }),
  });
  await assert.rejects(store.get("spend/2026-11"), /WRONGTYPE/);
  const down = createRedisStore({ url: "https://x", token: "t", fetchImpl: async () => ({ ok: false, status: 503 }) });
  await assert.rejects(down.increment("use/2026-11-10/dev", { explain: 1 }), /503/);
});

test("caducidad: diarios 40 días, mensuales 400, control nunca", () => {
  assert.equal(ttlFor("use/2026-11-10/dev"), 40 * 86_400);
  assert.equal(ttlFor("user/2026-11-10/a@b"), 40 * 86_400);
  assert.equal(ttlFor("voice_day/2026-11-10/dev"), 40 * 86_400);
  assert.equal(ttlFor("voice_month/2026-11"), 400 * 86_400);
  assert.equal(ttlFor("control"), null);
  assert.equal(ttlFor("voice_control"), null);
});

test("selección: con las variables de Upstash se usa Redis; sin nada, memoria con aviso", async () => {
  const warnings = [];
  const log = { warn: (text) => warnings.push(text) };
  const redis = await createStore({ UPSTASH_REDIS_REST_URL: "https://x", UPSTASH_REDIS_REST_TOKEN: "t" }, { log });
  assert.equal(redis.kind, "redis");
  assert.equal(warnings.length, 0);
  const memory = await createStore({}, { log });
  assert.ok(memory instanceof MemoryStore);
  assert.match(warnings[0], /no son topes reales/);
  const forced = await createStore({ STORE: "memory", UPSTASH_REDIS_REST_URL: "https://x", UPSTASH_REDIS_REST_TOKEN: "t" }, { log });
  assert.equal(forced.kind, "memory");
  await assert.rejects(createStore({ STORE: "redis" }, { log }), /UPSTASH_REDIS_REST_URL/);
});
