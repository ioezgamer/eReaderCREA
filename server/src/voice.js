// Voz natural para la lectura en voz alta, con ElevenLabs. La clave vive solo aquí (ELEVENLABS_API_KEY).
// Es un extra: si no hay clave, si se apaga con VOICE_ENABLED=false, si se llega al tope de caracteres
// o si ElevenLabs dice que no quedan créditos, responde 503 y la app sigue leyendo con la voz del
// dispositivo, sin cortar la lectura.
//
// Las voces que la app puede elegir son solo las de ELEVENLABS_VOICES (más la principal,
// ELEVENLABS_VOICE_ID): nadie puede pedir con esta clave una voz que no se haya aprobado aquí.

const API = "https://api.elevenlabs.io/v1/text-to-speech/";
const MAX_TEXT = 1200;
// Contexto que se manda con cada frase para que la entonación continúe la lectura.
const MAX_PREVIOUS_TEXT = 600;
// ElevenLabs encadena como mucho 3 audios anteriores, de las últimas 2 horas.
const STITCH_MAX = 3;
const STITCH_TTL_MS = 2 * 3_600_000;
const DEFAULT_VOICE = "21m00Tcm4TlvDq8ikWAM";
const VOICE_ID = /^[A-Za-z0-9]{10,40}$/;

/** "id=Nombre, id2=Otro nombre" (también vale "id:Nombre") -> [{ id, name }]. */
export function parseVoices(text) {
  const voices = [];
  for (const part of String(text ?? "").split(",")) {
    const match = /^\s*([A-Za-z0-9]{10,40})\s*(?:[=:]\s*(.*?))?\s*$/.exec(part);
    if (!match || voices.some((voice) => voice.id === match[1])) continue;
    voices.push({ id: match[1], name: (match[2] || "").slice(0, 60) || `Voz ${voices.length + 1}` });
  }
  return voices;
}

export function loadVoiceConfig(env = process.env) {
  const listed = parseVoices(env.ELEVENLABS_VOICES);
  const voiceId = env.ELEVENLABS_VOICE_ID?.trim() || listed[0]?.id || DEFAULT_VOICE;
  const voices = listed.some((voice) => voice.id === voiceId) ? listed : [{ id: voiceId, name: "Voz principal" }, ...listed];
  return {
    enabled: env.VOICE_ENABLED !== "false" && Boolean(env.ELEVENLABS_API_KEY),
    apiKey: env.ELEVENLABS_API_KEY?.trim() || "",
    voiceId,
    voices,
    // Flash v2.5: la más rápida y la que menos créditos gasta por carácter.
    model: env.ELEVENLABS_MODEL?.trim() || "eleven_flash_v2_5",
    outputFormat: env.ELEVENLABS_FORMAT || "mp3_22050_32",
    monthlyChars: Number(env.VOICE_MONTHLY_CHARS || 400000),
    deviceDailyChars: Number(env.VOICE_DEVICE_DAILY_CHARS || 60000),
    suspendHours: Number(env.SUSPEND_HOURS || 6),
    // Clave o voz mal puestas: se deja de insistir un rato, hasta que alguien lo corrija en Render.
    misconfiguredMinutes: Number(env.VOICE_MISCONFIGURED_MINUTES || 10),
    voiceSettings: loadVoiceSettings(env),
  };
}

/**
 * Expresividad de la voz (opcional): VOICE_STABILITY (más bajo = más expresiva), VOICE_STYLE,
 * VOICE_SIMILARITY, VOICE_SPEED y VOICE_SPEAKER_BOOST. Sin ninguna, se usan los ajustes de la voz.
 */
function loadVoiceSettings(env) {
  const number = (name, min, max) => {
    const value = Number(env[name]);
    return env[name] !== undefined && env[name] !== "" && Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : undefined;
  };
  const settings = {
    stability: number("VOICE_STABILITY", 0, 1),
    similarity_boost: number("VOICE_SIMILARITY", 0, 1),
    style: number("VOICE_STYLE", 0, 1),
    speed: number("VOICE_SPEED", 0.7, 1.2),
  };
  if (env.VOICE_SPEAKER_BOOST === "true" || env.VOICE_SPEAKER_BOOST === "false") {
    settings.use_speaker_boost = env.VOICE_SPEAKER_BOOST === "true";
  }
  const defined = Object.fromEntries(Object.entries(settings).filter(([, value]) => value !== undefined));
  if (Object.keys(defined).length === 0) return null;
  // Si solo se ajusta una parte, el resto queda en valores de narración razonables.
  return { stability: 0.5, similarity_boost: 0.75, ...defined };
}

/** Lee el código de error de ElevenLabs ({ detail: { status | code } }), si lo hay. */
function errorCode(body) {
  try {
    const detail = JSON.parse(body)?.detail;
    return String(detail?.status ?? detail?.code ?? "");
  } catch {
    return "";
  }
}

export function createVoice({ config, store, ServiceError, fetchImpl = fetch, now = () => new Date() }) {
  const day = () => now().toISOString().slice(0, 10);
  const month = () => now().toISOString().slice(0, 7);
  const voiceById = (id) => config.voices.find((voice) => voice.id === id);
  // Últimos audios de cada tablet y voz ({ id, text, at }), solo en memoria, para encadenar frases.
  const recent = new Map();
  const stitching = !config.model.startsWith("eleven_v3");

  /** Ids de los audios que terminan en la frase [previousText] (la que la tablet acaba de leer). */
  function previousIds(key, previousText) {
    const list = recent.get(key);
    if (!list || !previousText) return [];
    const fresh = list.filter((item) => now().getTime() - item.at < STITCH_TTL_MS);
    const last = fresh.map((item) => item.text).lastIndexOf(previousText);
    if (last < 0) return [];
    return fresh.slice(Math.max(0, last - STITCH_MAX + 1), last + 1).map((item) => item.id);
  }

  function remember(key, id, text) {
    if (!id) return;
    const list = (recent.get(key) ?? []).slice(-(STITCH_MAX * 2 - 1));
    list.push({ id, text, at: now().getTime() });
    recent.set(key, list);
    if (recent.size > 500) recent.delete(recent.keys().next().value);
  }

  async function unavailableReason() {
    if (!config.enabled) return "disabled";
    const control = await store.get("voice_control");
    if (control?.suspendedUntil && control.suspendedUntil > now().getTime()) return control.reason || "suspended";
    const used = await store.get(`voice_month/${month()}`);
    if ((used?.chars ?? 0) >= config.monthlyChars) return "budget";
    return null;
  }

  async function status(deviceId) {
    const reason = await unavailableReason();
    const used = await store.get(`voice_day/${day()}/${deviceId}`);
    return {
      available: reason === null,
      reason,
      remainingChars: Math.max(0, config.deviceDailyChars - (used?.chars ?? 0)),
      voices: config.voices,
      defaultVoice: config.voiceId,
    };
  }

  /** Devuelve el audio MP3 de [text], o lanza un ServiceError que la app entiende como "usa la voz del dispositivo". */
  async function speak(deviceId, rawText, rawLanguage, rawVoice, rawPrevious) {
    const text = String(rawText ?? "").trim();
    if (!text) throw new ServiceError(400, "invalid_input", "Texto vacío");
    if (text.length > MAX_TEXT) throw new ServiceError(413, "too_large", "Texto demasiado largo");
    const language = /^[a-z]{2}$/.test(String(rawLanguage ?? "")) ? String(rawLanguage) : undefined;
    // Una voz que no está en la lista aprobada se cambia por la principal.
    const voice = VOICE_ID.test(String(rawVoice ?? "")) && voiceById(String(rawVoice)) ? String(rawVoice) : config.voiceId;

    const reason = await unavailableReason();
    if (reason) throw new ServiceError(503, "unavailable", "Voz natural no disponible", { reason });
    const deviceUse = await store.get(`voice_day/${day()}/${deviceId}`);
    if ((deviceUse?.chars ?? 0) + text.length > config.deviceDailyChars) {
      throw new ServiceError(429, "daily_limit", "Límite diario de voz natural alcanzado");
    }

    const previous = String(rawPrevious ?? "").trim();
    const context = { previousText: previous ? previous.slice(-MAX_PREVIOUS_TEXT) : undefined };
    let usedVoice = voice;
    let response = await request(voice, text, language, { ...context, previousIds: previousIds(`${deviceId}|${voice}`, previous) });
    // Si una voz de la lista ya no existe en la cuenta, se lee con la principal en vez de fallar.
    if (!response.ok && voice !== config.voiceId) {
      const body = await response.clone().text().catch(() => "");
      if (errorCode(body) === "voice_not_found" || response.status === 404) {
        console.error(`La voz ${voice} no está en la cuenta de ElevenLabs; se usa la principal.`);
        usedVoice = config.voiceId;
        response = await request(usedVoice, text, language, { ...context, previousIds: previousIds(`${deviceId}|${usedVoice}`, previous) });
      }
    }
    if (!response.ok) throw await translateError(response);

    remember(`${deviceId}|${usedVoice}`, response.headers.get("request-id"), text);
    const audio = Buffer.from(await response.arrayBuffer());
    await store.increment(`voice_day/${day()}/${deviceId}`, { chars: text.length });
    await store.increment(`voice_month/${month()}`, { chars: text.length, calls: 1 });
    return audio;
  }

  async function request(voice, text, language, { previousText, previousIds = [] } = {}) {
    const body = {
      text,
      model_id: config.model,
      ...(language ? { language_code: language } : {}),
      ...(config.voiceSettings ? { voice_settings: config.voiceSettings } : {}),
      // Lo leído justo antes: así la frase continúa la entonación en vez de sonar como un comienzo.
      ...(previousText ? { previous_text: previousText } : {}),
      ...(stitching && previousIds.length > 0 ? { previous_request_ids: previousIds } : {}),
    };
    try {
      return await fetchImpl(`${API}${encodeURIComponent(voice)}?output_format=${config.outputFormat}`, {
        method: "POST",
        headers: { "xi-api-key": config.apiKey, "content-type": "application/json", accept: "audio/mpeg" },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(20_000),
      });
    } catch {
      throw new ServiceError(502, "upstream", "No se pudo contactar a ElevenLabs");
    }
  }

  async function pause(reason, minutes) {
    await store.set("voice_control", { reason, suspendedUntil: now().getTime() + minutes * 60_000 });
    return new ServiceError(503, "unavailable", "Voz natural no disponible", { reason });
  }

  async function translateError(response) {
    const body = await response.text().catch(() => "");
    const code = errorCode(body);
    if (response.status === 402 || code === "quota_exceeded" || code === "insufficient_credits") {
      // Sin créditos: se apaga un rato para no insistir; la app usa la voz del dispositivo.
      console.error("ElevenLabs: sin créditos.", body.slice(0, 200));
      return pause("no_credits", config.suspendHours * 60);
    }
    if (code === "invalid_api_key" || code === "missing_permissions" || response.status === 401 || response.status === 403) {
      console.error("ElevenLabs rechazó la clave:", body.slice(0, 200));
      return pause("misconfigured", config.misconfiguredMinutes);
    }
    if (code === "voice_not_found" || response.status === 404) {
      console.error("La voz principal no está en la cuenta de ElevenLabs:", body.slice(0, 200));
      return pause("voice_not_found", config.misconfiguredMinutes);
    }
    if (code === "model_not_found" || code === "invalid_model_id") {
      console.error("El modelo de ElevenLabs no es válido:", body.slice(0, 200));
      return pause("misconfigured", config.misconfiguredMinutes);
    }
    if (response.status === 429) return new ServiceError(429, "busy", "Voz natural ocupada");
    console.error("Error de ElevenLabs:", response.status, body.slice(0, 200));
    return new ServiceError(502, "upstream", "Error de la voz natural");
  }

  return { status, speak };
}
