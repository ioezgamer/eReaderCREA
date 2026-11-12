import assert from "node:assert/strict";
import { test } from "node:test";
import { createActivation, generateCode, normalizeCode, CODE_ALPHABET } from "../src/aventurasActivation.js";
import { createReports } from "../src/aventurasReport.js";
import { createServer } from "../src/index.js";
import { createService, loadConfig } from "../src/service.js";
import { MemoryStore, ttlFor } from "../src/store.js";

const SECRET = "secreto-de-prueba-de-32-caracteres-o-mas!!";
const DEVICE = "a".repeat(64);
const OTHER = "b".repeat(64);

const input = {
  version: 1, evaluated: true,
  candidates: Object.entries({ silabas: "SILABAS", rimas: "RIMAS", matematicas: "MATEMATICAS" }).map(([game, id]) => ({
    id: `${id}:INICIAL:1`, game, stage: "inicial", level: 1, objective: "Practicar.", reason: "Evaluación inicial.", recentAttempts: 0, recentPercent: -1,
  })),
};
const plan = { summary: "Practicaremos lectura y números.", choices: input.candidates.map((c) => ({ id: c.id, reason: "Practicar esta habilidad." })) };

function setup(env = {}, now = () => new Date("2026-11-12T12:00:00Z")) {
  const config = loadConfig({ CREA_AVENTURAS_SERVICE_TOKEN: "service-test", AUTH_MODE: "google", AVENTURAS_TOKEN_SECRET: SECRET, ...env });
  const store = new MemoryStore();
  const calls = [];
  const claude = { messages: { parse: async (params) => { calls.push(params); return { stop_reason: "end_turn", parsed_output: plan, usage: { input_tokens: 1, output_tokens: 1 } }; } } };
  const service = createService({ config, store, claude });
  const activation = createActivation({ config, store, ServiceError: service.ServiceError, now, log: () => {} });
  const reports = createReports({ config, store, ServiceError: service.ServiceError, now, log: () => {} });
  const admin = { email: "ana@creanicaragua.org" };
  return { config, store, service, activation, reports, calls, authenticate: async (req) => (req.headers["x-admin"] ? admin : null) };
}

async function listen(t, s) {
  const server = createServer(s);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;
  const call = (path, { method = "POST", token, device = DEVICE, body, admin = false, ip } = {}) => fetch(base + path, {
    method,
    headers: {
      "content-type": "application/json",
      ...(device ? { "x-device-id": device } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(admin ? { "x-admin": "1", authorization: "Bearer sesion-google" } : {}),
      ...(ip ? { "x-forwarded-for": ip } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { call };
}

async function newCode(call, extra = {}) {
  const r = await call("/v1/aventuras/codes", { admin: true, body: { label: "Escuela X", maxDevices: 2, days: 30, ...extra } });
  assert.equal(r.status, 200);
  return r.json();
}

test("el código tiene 12 símbolos sin 0, O, 1, I ni L, y se normaliza como en la app", () => {
  assert.equal(CODE_ALPHABET.length, 31);
  for (let i = 0; i < 200; i++) {
    const code = generateCode();
    assert.match(code, /^[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$/);
    assert.equal(normalizeCode(code.toLowerCase().replace(/-/g, " ")), code);
  }
  assert.equal(normalizeCode("abcd efgh jkmn"), "ABCD-EFGH-JKMN");
  for (const bad of ["", "ABCD-EFGH", "ABCD-EFGH-JKMNP", "ABCD-EFGH-JKM0", "ABCD-EFGH-JKMI", null, undefined, 12345]) assert.equal(normalizeCode(bad), null);
});

test("sin AVENTURAS_TOKEN_SECRET (o con uno corto) la activación queda apagada", async (t) => {
  const s = setup({ AVENTURAS_TOKEN_SECRET: "corto" });
  assert.equal(s.activation.enabled, false);
  const { call } = await listen(t, s);
  const r = await call("/v1/aventuras/activate", { body: { code: "ABCD-EFGH-JKMN", device: DEVICE } });
  assert.equal(r.status, 503);
  assert.equal((await r.json()).code, "activation_disabled");
});

test("crear un código exige la sesión de Google y no guarda el código en claro", async (t) => {
  const s = setup();
  const { call } = await listen(t, s);
  assert.equal((await call("/v1/aventuras/codes", { body: { label: "X", maxDevices: 1, days: 1 } })).status, 401);
  const made = await newCode(call);
  assert.match(made.code, /^[2-9A-Z]{4}(-[2-9A-Z]{4}){2}$/);
  assert.match(made.id, /^[a-f0-9]{16}$/);
  // Ni el código ni nada que lo reconstruya está guardado.
  const stored = JSON.stringify([...s.store.data.entries()]);
  assert.equal(stored.includes(made.code), false);
  assert.equal(stored.includes(made.code.replaceAll("-", "")), false);
  // Datos inválidos.
  for (const body of [{ label: "", maxDevices: 1, days: 1 }, { label: "X", maxDevices: 0, days: 1 }, { label: "X", maxDevices: 1, days: 9999 }, { label: "X", maxDevices: 1, days: 1, extra: 1 }]) {
    assert.equal((await call("/v1/aventuras/codes", { admin: true, body })).status, 400);
  }
});

test("activar da un token atado a la tablet; con él se consulta el tutor y se reporta", async (t) => {
  const s = setup();
  const { call } = await listen(t, s);
  const { code } = await newCode(call);
  const r = await call("/v1/aventuras/activate", { body: { code: code.toLowerCase(), device: DEVICE } });
  assert.equal(r.status, 200);
  const { result } = await r.json();
  assert.match(result.token, /^v1\.[\w-]+\.[\w-]+$/);
  assert.equal(result.label, "Escuela X");
  assert.equal(result.expiresAt, Math.floor(new Date("2026-12-12T12:00:00Z").getTime() / 1000)); // el código vence antes que 90 días
  // Tutor con el token.
  const ask = await call("/v1/aventuras/tutor", { token: result.token, body: input });
  assert.equal(ask.status, 200);
  assert.deepEqual((await ask.json()).result, plan);
  assert.equal(s.calls.length, 1);
  // Reporte con el mismo token.
  const report = await call("/v1/aventuras/tutor/report", {
    token: result.token,
    body: { version: 1, reason: "inadecuado_edad", summary: "Texto.", choices: [{ id: "RIMAS:INICIAL:1", reason: "Porque sí." }], app: "1.12.0" },
  });
  assert.equal(report.status, 200);
  // El mismo token en OTRA tablet no sirve.
  assert.equal((await call("/v1/aventuras/tutor", { token: result.token, device: OTHER, body: input })).status, 401);
  // Sin código de tablet válido: 400.
  assert.equal((await call("/v1/aventuras/tutor", { token: result.token, device: "xyz", body: input })).status, 400);
});

test("códigos desconocidos, mal formados, revocados o vencidos no activan", async (t) => {
  let now = new Date("2026-11-12T12:00:00Z");
  const s = setup({}, () => now);
  const { call } = await listen(t, s);
  assert.equal((await call("/v1/aventuras/activate", { body: { code: "ABCD-EFGH-JKMN", device: DEVICE } })).status, 404);
  assert.equal((await call("/v1/aventuras/activate", { body: { code: "ABCD", device: DEVICE } })).status, 400);
  assert.equal((await call("/v1/aventuras/activate", { body: { code: "ABCD-EFGH-JKMN", device: "no-es-hex" } })).status, 400);
  assert.equal((await call("/v1/aventuras/activate", { body: {} })).status, 400);

  const a = await newCode(call);
  const b = await newCode(call, { label: "Otro", days: 1 });
  await call("/v1/aventuras/codes/revoke", { admin: true, body: { id: a.id } });
  assert.equal((await call("/v1/aventuras/activate", { body: { code: a.code, device: DEVICE } })).status, 404);
  now = new Date("2026-11-14T12:00:00Z"); // b venció
  assert.equal((await call("/v1/aventuras/activate", { body: { code: b.code, device: DEVICE } })).status, 404);
});

test("revocar el código apaga los tokens ya entregados", async (t) => {
  const s = setup();
  const { call } = await listen(t, s);
  const { code, id } = await newCode(call);
  const { result } = await (await call("/v1/aventuras/activate", { body: { code, device: DEVICE } })).json();
  assert.equal((await call("/v1/aventuras/tutor", { token: result.token, body: input })).status, 200);
  assert.equal((await call("/v1/aventuras/codes/revoke", { admin: true, body: { id } })).status, 200);
  assert.equal((await call("/v1/aventuras/tutor", { token: result.token, body: input })).status, 401);
  assert.equal((await call("/v1/aventuras/codes/revoke", { admin: true, body: { id: "0".repeat(16) } })).status, 404);
  assert.equal((await call("/v1/aventuras/codes/revoke", { admin: true, body: { id: "no" } })).status, 400);
});

test("un token alterado, vencido o de otro servidor no vale", async (t) => {
  let now = new Date("2026-11-12T12:00:00Z");
  const s = setup({ AVENTURAS_TOKEN_DAYS: "1" }, () => now);
  const { call } = await listen(t, s);
  const { code } = await newCode(call);
  const { result } = await (await call("/v1/aventuras/activate", { body: { code, device: DEVICE } })).json();
  const [, payload, sig] = result.token.split(".");
  const tampered = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(payload, "base64url")), e: 9999999999 })).toString("base64url");
  for (const bad of [`v1.${tampered}.${sig}`, `v1.${payload}.${sig.slice(0, -2)}AA`, `v1.${payload}`, "v1.", "v1.a.b.c", result.token + "x"]) {
    assert.equal((await call("/v1/aventuras/tutor", { token: bad, body: input })).status, 401, bad);
  }
  // Firmado con otro secreto.
  const other = setup({ AVENTURAS_TOKEN_SECRET: "otro-secreto-distinto-de-32-caracteres!!" });
  assert.equal(await other.activation.verifyToken(result.token, DEVICE), null);
  // Vencido (duran 1 día).
  now = new Date("2026-11-13T12:00:01Z");
  assert.equal((await call("/v1/aventuras/tutor", { token: result.token, body: input })).status, 401);
});

test("el código tiene un número máximo de tablets; la misma tablet puede reactivar", async (t) => {
  const s = setup();
  const { call } = await listen(t, s);
  const { code } = await newCode(call, { maxDevices: 2 });
  const go = (device) => call("/v1/aventuras/activate", { body: { code, device } });
  assert.equal((await go("1".repeat(64))).status, 200);
  assert.equal((await go("2".repeat(64))).status, 200);
  assert.equal((await go("1".repeat(64))).status, 200); // reactivar no gasta cupo
  const third = await go("3".repeat(64));
  assert.equal(third.status, 409);
  assert.equal((await third.json()).code, "code_exhausted");
  assert.equal((await go("2".repeat(64))).status, 200);
  const list = await (await call("/v1/aventuras/codes", { method: "GET", device: "", admin: true })).json();
  assert.equal(list.codes[0].devices, 2);
  assert.equal(list.codes[0].maxDevices, 2);
});

test("se frenan los intentos de adivinar códigos por tablet, por red y en total", async (t) => {
  const s = setup({ AVENTURAS_ACTIVATE_DEVICE_HOURLY: "3", AVENTURAS_ACTIVATE_IP_HOURLY: "5", AVENTURAS_ACTIVATE_HOURLY: "8" });
  const { call } = await listen(t, s);
  const bad = (device, ip) => call("/v1/aventuras/activate", { device: null, ip, body: { code: "ABCD-EFGH-JKMN", device } });
  for (let i = 0; i < 3; i++) assert.equal((await bad("1".repeat(64), "10.0.0.1")).status, 404);
  assert.equal((await bad("1".repeat(64), "10.0.0.1")).status, 429); // misma tablet
  for (let i = 0; i < 2; i++) assert.equal((await bad(String(i + 2).repeat(64), "10.0.0.1")).status, 404);
  assert.equal((await bad("5".repeat(64), "10.0.0.1")).status, 429); // misma red (el intento 4 de la tablet 1 también contó)
  // Intentos 6 a 8 del total (otras redes y otras tablets): aún pasan; el noveno pasa el tope por hora.
  for (let i = 0; i < 3; i++) assert.equal((await bad(String(i + 6).repeat(64), `10.0.1.${i}`)).status, 404);
  assert.equal((await bad("9".repeat(64), "10.0.2.9")).status, 429);
});

test("el tope diario es por código: todas sus tablets comparten", async (t) => {
  const s = setup({ AVENTURAS_CODE_DAILY_LIMIT: "3" });
  const { call } = await listen(t, s);
  const { code } = await newCode(call, { maxDevices: 3 });
  const tokens = [];
  for (const d of ["1", "2", "3"]) tokens.push((await (await call("/v1/aventuras/activate", { body: { code, device: d.repeat(64) } })).json()).result.token);
  for (let i = 0; i < 3; i++) assert.equal((await call("/v1/aventuras/tutor", { token: tokens[i], device: String(i + 1).repeat(64), body: input })).status, 200);
  const over = await call("/v1/aventuras/tutor", { token: tokens[0], device: "1".repeat(64), body: input });
  assert.equal(over.status, 429);
  assert.equal(s.calls.length, 3); // la rechazada no gastó una consulta a Claude
});

test("las llaves anteriores siguen sirviendo y sin activación el servidor no se rompe", async (t) => {
  const s = setup({ CREA_AVENTURAS_APP_TOKEN: "app-vieja" });
  const { call } = await listen(t, s);
  assert.equal((await call("/v1/aventuras/tutor", { token: "service-test", body: input })).status, 200);
  assert.equal((await call("/v1/aventuras/tutor", { token: "app-vieja", body: input })).status, 200);
  assert.equal((await call("/v1/aventuras/tutor", { token: "otra", body: input })).status, 401);
  assert.equal((await call("/v1/aventuras/tutor", { body: input })).status, 401);
  // Servidor sin activation: la ruta de activar responde 503 y un token v1 no se confunde con una llave.
  const bare = setup();
  const { call: bareCall } = await listen(t, { ...bare, activation: undefined });
  assert.equal((await bareCall("/v1/aventuras/activate", { body: { code: "ABCD-EFGH-JKMN", device: DEVICE } })).status, 503);
  assert.equal((await bareCall("/v1/aventuras/tutor", { token: "v1.a.b", body: input })).status, 503);
});

test("los contadores de la activación caducan solos", () => {
  assert.ok(ttlFor("aventuras_activate_try/2026-11-12T12/all") <= 2 * 86_400);
  assert.ok(ttlFor("aventuras_code_day/2026-11-12/abcdef0123456789") > 0);
  // Los códigos y sus tablets no caducan por sí solos: los vence su propio campo o se revocan.
  assert.equal(ttlFor("aventuras_code/abcdef0123456789"), null);
});
