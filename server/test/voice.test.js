import assert from "node:assert/strict";
import { test } from "node:test";
import { createService, loadConfig } from "../src/service.js";
import { MemoryStore } from "../src/store.js";
import { createVoice, loadVoiceConfig } from "../src/voice.js";

const { ServiceError } = createService({ config: loadConfig({ MOCK_AI: "1", AUTH_MODE: "mock" }), store: new MemoryStore() });

function fakeElevenLabs(respond) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init, body: JSON.parse(init.body) });
    return respond ? respond(url, init) : new Response(Buffer.from("ID3audio"), { status: 200, headers: { "content-type": "audio/mpeg" } });
  };
  return { calls, fetchImpl };
}

function setup({ env = {}, respond, clock } = {}) {
  const store = new MemoryStore();
  const api = fakeElevenLabs(respond);
  const config = loadVoiceConfig({ ELEVENLABS_API_KEY: "clave", ...env });
  const voice = createVoice({ config, store, ServiceError, fetchImpl: api.fetchImpl, now: clock });
  return { voice, store, api, config };
}

test("sin clave la voz natural está apagada y la app usará la voz del dispositivo", async () => {
  const store = new MemoryStore();
  const voice = createVoice({ config: loadVoiceConfig({}), store, ServiceError });
  assert.deepEqual(await voice.status("dev1"), { available: false, reason: "disabled", remainingChars: 60000 });
  await assert.rejects(voice.speak("dev1", "Hola", "es"), (error) => error.status === 503 && error.extra.reason === "disabled");
});

test("lee el texto con Flash v2.5, envía el idioma y cuenta los caracteres", async () => {
  const { voice, store, api } = setup();
  const audio = await voice.speak("dev1", "  Érase una vez.  ", "es");
  assert.equal(audio.toString(), "ID3audio");
  const call = api.calls[0];
  assert.match(call.url, /text-to-speech\/21m00Tcm4TlvDq8ikWAM\?output_format=mp3_22050_32$/);
  assert.equal(call.init.headers["xi-api-key"], "clave");
  assert.deepEqual(call.body, { text: "Érase una vez.", model_id: "eleven_flash_v2_5", language_code: "es" });
  const month = new Date().toISOString().slice(0, 7);
  assert.equal((await store.get(`voice_month/${month}`)).chars, 14);
});

test("un idioma raro no se envía", async () => {
  const { voice, api } = setup();
  await voice.speak("dev1", "Hello", "en-US<script>");
  assert.equal(api.calls[0].body.language_code, undefined);
});

test("si ElevenLabs dice que no hay créditos, se suspende y no vuelve a llamar", async () => {
  const { voice, api } = setup({
    respond: () => new Response(JSON.stringify({ detail: { status: "quota_exceeded" } }), { status: 401 }),
  });
  await assert.rejects(voice.speak("dev1", "Hola", "es"), (error) => error.status === 503 && error.extra.reason === "suspended");
  await assert.rejects(voice.speak("dev1", "Hola otra vez", "es"), (error) => error.extra.reason === "suspended");
  assert.equal(api.calls.length, 1);
  assert.equal((await voice.status("dev1")).available, false);
});

test("una clave rechazada se informa como mal configurada, sin suspender", async () => {
  const { voice } = setup({ respond: () => new Response(JSON.stringify({ detail: { status: "invalid_api_key" } }), { status: 401 }) });
  await assert.rejects(voice.speak("dev1", "Hola", "es"), (error) => error.extra.reason === "misconfigured");
});

test("respeta el tope diario por dispositivo y el mensual", async () => {
  const daily = setup({ env: { VOICE_DEVICE_DAILY_CHARS: "10" } });
  await daily.voice.speak("dev1", "12345", "es");
  await assert.rejects(daily.voice.speak("dev1", "123456", "es"), (error) => error.status === 429 && error.code === "daily_limit");
  await daily.voice.speak("dev2", "123456", "es");

  const monthly = setup({ env: { VOICE_MONTHLY_CHARS: "5" } });
  await monthly.voice.speak("dev1", "12345", "es");
  await assert.rejects(monthly.voice.speak("dev2", "a", "es"), (error) => error.extra.reason === "budget");
});

test("rechaza textos vacíos o demasiado largos sin llamar a ElevenLabs", async () => {
  const { voice, api } = setup();
  await assert.rejects(voice.speak("dev1", "   ", "es"), (error) => error.status === 400);
  await assert.rejects(voice.speak("dev1", "a".repeat(1201), "es"), (error) => error.status === 413);
  assert.equal(api.calls.length, 0);
});
