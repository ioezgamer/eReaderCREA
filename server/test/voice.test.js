import assert from "node:assert/strict";
import { test } from "node:test";
import { createService, loadConfig } from "../src/service.js";
import { MemoryStore } from "../src/store.js";
import { createVoice, loadVoiceConfig, parseVoices } from "../src/voice.js";

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
  const status = await voice.status("dev1");
  assert.equal(status.available, false);
  assert.equal(status.reason, "disabled");
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
  await assert.rejects(voice.speak("dev1", "Hola", "es"), (error) => error.status === 503 && error.extra.reason === "no_credits");
  await assert.rejects(voice.speak("dev1", "Hola otra vez", "es"), (error) => error.extra.reason === "no_credits");
  assert.equal(api.calls.length, 1);
  assert.equal((await voice.status("dev1")).available, false);
});

test("una clave rechazada (también con 400) pausa unos minutos como mal configurada", async () => {
  const { voice, api } = setup({ respond: () => new Response(JSON.stringify({ detail: { code: "invalid_api_key", message: "API keys start with sk_" } }), { status: 400 }) });
  await assert.rejects(voice.speak("dev1", "Hola", "es"), (error) => error.extra.reason === "misconfigured");
  const status = await voice.status("dev1");
  assert.equal(status.available, false);
  assert.equal(status.reason, "misconfigured");
  await assert.rejects(voice.speak("dev1", "Hola", "es"), (error) => error.extra.reason === "misconfigured");
  assert.equal(api.calls.length, 1);
});

test("un mensaje que menciona créditos sin ser falta de créditos no apaga la voz", async () => {
  const { voice } = setup({ respond: () => new Response(JSON.stringify({ detail: { code: "rate_limited", message: "Check your credits page" } }), { status: 500 }) });
  await assert.rejects(voice.speak("dev1", "Hola", "es"), (error) => error.status === 502);
  assert.equal((await voice.status("dev1")).available, true);
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

test("lee la lista de voces de ELEVENLABS_VOICES y pone la principal primero", () => {
  assert.deepEqual(parseVoices("abcdefghij0123456789=Jhenny, xyzXYZ0123456789:Fernando , mal, abcdefghij0123456789=Repetida"), [
    { id: "abcdefghij0123456789", name: "Jhenny" },
    { id: "xyzXYZ0123456789", name: "Fernando" },
  ]);
  const config = loadVoiceConfig({ ELEVENLABS_API_KEY: "k", ELEVENLABS_VOICE_ID: "principal0123456789", ELEVENLABS_VOICES: "otra0123456789=Otra" });
  assert.deepEqual(config.voices.map((voice) => voice.id), ["principal0123456789", "otra0123456789"]);
  const sinPrincipal = loadVoiceConfig({ ELEVENLABS_API_KEY: "k", ELEVENLABS_VOICES: "otra0123456789=Otra" });
  assert.equal(sinPrincipal.voiceId, "otra0123456789");
});

test("usa la voz elegida si está aprobada; si no, la principal", async () => {
  const { voice, api } = setup({ env: { ELEVENLABS_VOICE_ID: "principal0123456789", ELEVENLABS_VOICES: "otra0123456789=Otra" } });
  const status = await voice.status("dev1");
  assert.deepEqual(status.voices.map((v) => v.name), ["Voz principal", "Otra"]);
  assert.equal(status.defaultVoice, "principal0123456789");
  await voice.speak("dev1", "Hola", "es", "otra0123456789");
  await voice.speak("dev1", "Hola", "es", "noaprobada0123456789");
  assert.match(api.calls[0].url, /\/otra0123456789\?/);
  assert.match(api.calls[1].url, /\/principal0123456789\?/);
});

test("si la voz elegida ya no existe en la cuenta, lee con la principal", async () => {
  const { voice, api } = setup({
    env: { ELEVENLABS_VOICE_ID: "principal0123456789", ELEVENLABS_VOICES: "otra0123456789=Otra" },
    respond: (url) => url.includes("otra0123456789")
      ? new Response(JSON.stringify({ detail: { status: "voice_not_found" } }), { status: 404 })
      : new Response(Buffer.from("ID3principal"), { status: 200 }),
  });
  const audio = await voice.speak("dev1", "Hola", "es", "otra0123456789");
  assert.equal(audio.toString(), "ID3principal");
  assert.equal(api.calls.length, 2);
});

function chainedElevenLabs() {
  let next = 0;
  return () => new Response(Buffer.from("ID3audio"), { status: 200, headers: { "request-id": `req${++next}` } });
}

test("cada frase lleva la anterior y encadena los audios previos de la misma tablet", async () => {
  const { voice, api } = setup({ respond: chainedElevenLabs() });
  await voice.speak("dev1", "Primera frase.", "es");
  await voice.speak("dev1", "Segunda frase.", "es", undefined, "Primera frase.");
  await voice.speak("dev1", "Tercera frase.", "es", undefined, "Segunda frase.");
  await voice.speak("dev1", "Cuarta frase.", "es", undefined, "Tercera frase.");
  await voice.speak("dev1", "Quinta frase.", "es", undefined, "Cuarta frase.");
  assert.equal(api.calls[0].body.previous_text, undefined);
  assert.equal(api.calls[0].body.previous_request_ids, undefined);
  assert.equal(api.calls[1].body.previous_text, "Primera frase.");
  assert.deepEqual(api.calls[1].body.previous_request_ids, ["req1"]);
  assert.deepEqual(api.calls[2].body.previous_request_ids, ["req1", "req2"]);
  assert.deepEqual(api.calls[4].body.previous_request_ids, ["req2", "req3", "req4"], "como mucho 3");
});

test("tras un salto (la frase anterior no es la última leída) solo manda el texto, sin encadenar", async () => {
  const { voice, api } = setup({ respond: chainedElevenLabs() });
  await voice.speak("dev1", "Capítulo uno.", "es");
  await voice.speak("dev2", "Otra tablet.", "es", undefined, "Capítulo uno.");
  await voice.speak("dev1", "Capítulo cinco.", "es", undefined, "Final del capítulo cuatro.");
  assert.equal(api.calls[1].body.previous_request_ids, undefined, "no mezcla tablets");
  assert.equal(api.calls[2].body.previous_text, "Final del capítulo cuatro.");
  assert.equal(api.calls[2].body.previous_request_ids, undefined);
});

test("con eleven_v3 manda el contexto pero no encadena (no lo admite)", async () => {
  const { voice, api } = setup({ env: { ELEVENLABS_MODEL: "eleven_v3" }, respond: chainedElevenLabs() });
  await voice.speak("dev1", "Uno.", "es");
  await voice.speak("dev1", "Dos.", "es", undefined, "Uno.");
  assert.equal(api.calls[1].body.previous_text, "Uno.");
  assert.equal(api.calls[1].body.previous_request_ids, undefined);
});

test("los ajustes de expresividad de Render se mandan con valores válidos", async () => {
  const { voice, api } = setup({ env: { VOICE_STABILITY: "0.4", VOICE_STYLE: "0.15", VOICE_SPEED: "3", VOICE_SPEAKER_BOOST: "true" } });
  await voice.speak("dev1", "Hola.", "es");
  assert.deepEqual(api.calls[0].body.voice_settings, { stability: 0.4, similarity_boost: 0.75, style: 0.15, speed: 1.2, use_speaker_boost: true });
  const plain = setup();
  await plain.voice.speak("dev1", "Hola.", "es");
  assert.equal(plain.api.calls[0].body.voice_settings, undefined, "sin variables usa los ajustes de la voz");
});
