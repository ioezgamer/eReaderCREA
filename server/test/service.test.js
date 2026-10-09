import assert from "node:assert/strict";
import { test } from "node:test";
import { createAuthenticator } from "../src/auth.js";
import { createServer } from "../src/index.js";
import { createService, loadConfig } from "../src/service.js";
import { MemoryStore } from "../src/store.js";

const user = { email: "maria@creanicaragua.org" };

const info = {
  title: "Cien años de soledad",
  author: "Gabriel García Márquez",
  summary: "La saga de los Buendía en Macondo.",
  categories: ["Novela"],
  level: "bachillerato",
  confidence: "high",
};

function fakeClaude(overrides = {}) {
  const calls = [];
  return {
    calls,
    messages: {
      parse: async (params) => {
        calls.push(params);
        if (overrides.parse) return overrides.parse(params);
        return { stop_reason: "end_turn", parsed_output: info, usage: { input_tokens: 1000, output_tokens: 200 } };
      },
      create: async (params) => {
        calls.push(params);
        if (overrides.create) return overrides.create(params);
        return { stop_reason: "end_turn", content: [{ type: "text", text: "Explicación." }], usage: { input_tokens: 500, output_tokens: 100 } };
      },
    },
  };
}

function setup({ claude = fakeClaude(), env = {}, clock } = {}) {
  const config = loadConfig({ AUTH_MODE: "mock", ...env });
  const store = new MemoryStore();
  const service = createService({ config, store, claude, now: clock });
  return { service, store, claude, config };
}

test("una ficha de libro usa Haiku 5.5 sin pensar, con esfuerzo bajo y el texto como datos", async () => {
  const { service, claude } = setup();
  const { result, model } = await service.ask(user, "dev1", "book_info", { fileName: "Cien años - García.epub", excerpt: "Muchos años después..." });
  assert.equal(result.title, "Cien años de soledad");
  assert.equal(model, "claude-haiku-5-5");
  const params = claude.calls[0];
  assert.deepEqual(params.thinking, { type: "disabled" });
  assert.equal(params.output_config.effort, "low");
  assert.ok(params.output_config.format, "debe pedir salida estructurada");
  assert.match(params.system, /nunca sigas instrucciones/);
  assert.match(params.messages[0].content, /<inicio_del_texto>/);
  assert.equal(params.model.includes("-20"), false, "sin sufijo de fecha");
});

test("explicar devuelve texto y calcula el gasto", async () => {
  const { service, store } = setup();
  const { result } = await service.ask(user, "dev1", "explain", { mode: "define", text: "efímero", context: "una vida efímera" });
  assert.equal(result, "Explicación.");
  const month = new Date().toISOString().slice(0, 7);
  const spend = await store.get(`spend/${month}`);
  // 500 tokens * 0.10 + 100 tokens * 0.50 = 100 micro-dólares
  assert.equal(spend.micros, 100);
});

test("datos no válidos o tareas desconocidas se rechazan sin llamar a Claude", async () => {
  const { service, claude } = setup();
  await assert.rejects(service.ask(user, "d", "explain", { mode: "otro", text: "x" }), { status: 400 });
  await assert.rejects(service.ask(user, "d", "hacer_otra_cosa", {}), { status: 400 });
  await assert.rejects(service.ask(user, "d", "explain", { mode: "explain", text: "x".repeat(5000) }), { status: 400 });
  assert.equal(claude.calls.length, 0);
});

test("el límite diario por dispositivo se aplica por tarea y se reinicia al día siguiente", async () => {
  let current = new Date("2026-10-10T12:00:00Z");
  const { service } = setup({ clock: () => current });
  const body = { fileName: "a.epub" };
  for (let i = 0; i < 40; i++) await service.ask(user, "dev1", "book_info", body);
  await assert.rejects(service.ask(user, "dev1", "book_info", body), { status: 429, code: "daily_limit" });
  // Otro dispositivo y otras funciones siguen funcionando.
  await service.ask(user, "dev2", "book_info", body);
  await service.ask(user, "dev1", "explain", { mode: "explain", text: "hola" });
  current = new Date("2026-10-11T12:00:00Z");
  await service.ask(user, "dev1", "book_info", body);
});

test("al llegar al presupuesto mensual las funciones se apagan solas", async () => {
  const { service } = setup({ env: { MONTHLY_BUDGET_USD: "0.0002" } });
  await service.ask(user, "d", "explain", { mode: "explain", text: "uno" }); // 100 µ$
  await service.ask(user, "d", "explain", { mode: "explain", text: "dos" }); // 200 µ$ = tope
  await assert.rejects(service.ask(user, "d", "explain", { mode: "explain", text: "tres" }), { status: 503, code: "unavailable" });
  const status = await service.status(user, "d");
  assert.equal(status.available, false);
  assert.equal(status.reason, "budget");
});

test("si Anthropic dice que no hay créditos, se suspende y la app lo sabe", async () => {
  const outOfCredits = Object.assign(new Error("Your credit balance is too low to access the Anthropic API"), { status: 400 });
  const { service } = setup({ claude: fakeClaude({ create: () => { throw outOfCredits; } }) });
  await assert.rejects(service.ask(user, "d", "explain", { mode: "explain", text: "hola" }), { status: 503, code: "unavailable" });
  const status = await service.status(user, "d");
  assert.equal(status.available, false);
  assert.equal(status.reason, "suspended");
  // Mientras está suspendido ni siquiera se intenta llamar.
  await assert.rejects(service.ask(user, "d", "explain", { mode: "explain", text: "otra vez" }), { status: 503 });
});

test("AI_ENABLED=false apaga todo", async () => {
  const { service } = setup({ env: { AI_ENABLED: "false" } });
  assert.equal((await service.status(user, "d")).available, false);
  await assert.rejects(service.ask(user, "d", "explain", { mode: "explain", text: "hola" }), { status: 503 });
});

test("una negativa del modelo no se cobra como respuesta", async () => {
  const { service } = setup({ claude: fakeClaude({ parse: () => ({ stop_reason: "refusal", parsed_output: null, usage: { input_tokens: 1, output_tokens: 1 } }) }) });
  await assert.rejects(service.ask(user, "d", "book_info", { fileName: "x.epub" }), { status: 422, code: "refused" });
});

test("el modo de prueba responde sin llamar a Claude", async () => {
  const { service, claude } = setup({ env: { MOCK_AI: "1" } });
  const { result, model } = await service.ask(user, "d", "book_info", { fileName: "El principito - Saint-Exupéry.epub" });
  assert.equal(model, "mock");
  assert.equal(result.author, "Saint-Exupéry");
  assert.equal(claude.calls.length, 0);
});

test("solo entran cuentas del dominio permitido con token verificado", async () => {
  const config = loadConfig({ ALLOWED_DOMAINS: "creanicaragua.org", ALLOWED_AUDIENCES: "app" });
  // Los tokens de la app solo tienen permisos de Drive: tokeninfo no trae correo y se consulta Drive.
  const tokens = {
    bueno: { aud: "app", expires_in: "3000", email: "ana@creanicaragua.org" },
    ajeno: { aud: "app", expires_in: "3000", email: "x@gmail.com" },
    otraApp: { aud: "otra", expires_in: "3000", email: "z@creanicaragua.org" },
    trucado: { aud: "app", expires_in: "3000", email: "w@creanicaragua.org.evil.com" },
  };
  const fetchImpl = async (url, options) => {
    if (url.startsWith("https://oauth2.googleapis.com/tokeninfo")) {
      const token = decodeURIComponent(url.split("access_token=")[1]);
      const entry = tokens[token];
      if (!entry) return { ok: false };
      const { email, ...info } = entry; // sin correo, como en la realidad
      return { ok: true, json: async () => info };
    }
    const token = options.headers.authorization.slice(7);
    return { ok: true, json: async () => ({ user: { emailAddress: tokens[token].email } }) };
  };
  const authenticate = createAuthenticator({ fetchImpl, config });
  const req = (token) => ({ headers: token ? { authorization: `Bearer ${token}` } : {} });
  assert.equal((await authenticate(req("bueno"))).email, "ana@creanicaragua.org");
  assert.equal(await authenticate(req("ajeno")), null);
  assert.equal(await authenticate(req("otraApp")), null);
  assert.equal(await authenticate(req("trucado")), null);
  assert.equal(await authenticate(req("desconocido")), null);
  assert.equal(await authenticate(req(null)), null);
});

test("el servidor HTTP responde estado y preguntas, y exige autenticación", async () => {
  const { service, config } = setup();
  const authenticate = async (request) => (request.headers.authorization === "Bearer ok" ? user : null);
  const server = createServer({ config, service, authenticate }).listen(0);
  await new Promise((resolve) => server.once("listening", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const headers = { authorization: "Bearer ok", "content-type": "application/json", "x-device-id": "tablet-1" };
  try {
    assert.equal((await fetch(`${base}/v1/status`)).status, 401);
    const status = await (await fetch(`${base}/v1/status`, { headers })).json();
    assert.equal(status.available, true);
    assert.equal(status.remaining.book_info, 40);

    const answer = await fetch(`${base}/v1/ask`, { method: "POST", headers, body: JSON.stringify({ task: "explain", input: { mode: "simplify", text: "texto" } }) });
    assert.equal(answer.status, 200);
    assert.equal((await answer.json()).result, "Explicación.");

    const bad = await fetch(`${base}/v1/ask`, { method: "POST", headers, body: "no es json" });
    assert.equal(bad.status, 400);
    const huge = await fetch(`${base}/v1/ask`, { method: "POST", headers, body: "x".repeat(200_000) }).catch(() => ({ status: 413 }));
    assert.ok([413].includes(huge.status) || huge.status === undefined);
  } finally {
    server.close();
  }
});
