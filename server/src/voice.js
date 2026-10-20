// Voz natural para la lectura en voz alta, con ElevenLabs. La clave vive solo aquí (ELEVENLABS_API_KEY).
// Es un extra: si no hay clave, si se apaga con VOICE_ENABLED=false, si se llega al tope de caracteres
// o si ElevenLabs dice que no quedan créditos, responde 503 y la app sigue leyendo con la voz del
// dispositivo, sin cortar la lectura.

const API = "https://api.elevenlabs.io/v1/text-to-speech/";
const MAX_TEXT = 1200;

export function loadVoiceConfig(env = process.env) {
  return {
    enabled: env.VOICE_ENABLED !== "false" && Boolean(env.ELEVENLABS_API_KEY),
    apiKey: env.ELEVENLABS_API_KEY || "",
    // Voz prediseñada multilingüe; se puede cambiar por una voz en español de la biblioteca de ElevenLabs.
    voiceId: env.ELEVENLABS_VOICE_ID || "21m00Tcm4TlvDq8ikWAM",
    // Flash v2.5: la más rápida y la que menos créditos gasta por carácter.
    model: env.ELEVENLABS_MODEL || "eleven_flash_v2_5",
    outputFormat: env.ELEVENLABS_FORMAT || "mp3_22050_32",
    monthlyChars: Number(env.VOICE_MONTHLY_CHARS || 400000),
    deviceDailyChars: Number(env.VOICE_DEVICE_DAILY_CHARS || 60000),
    suspendHours: Number(env.SUSPEND_HOURS || 6),
  };
}

export function createVoice({ config, store, ServiceError, fetchImpl = fetch, now = () => new Date() }) {
  const day = () => now().toISOString().slice(0, 10);
  const month = () => now().toISOString().slice(0, 7);

  async function unavailableReason() {
    if (!config.enabled) return "disabled";
    const control = await store.get("voice_control");
    if (control?.suspendedUntil && control.suspendedUntil > now().getTime()) return "suspended";
    const used = await store.get(`voice_month/${month()}`);
    if ((used?.chars ?? 0) >= config.monthlyChars) return "budget";
    return null;
  }

  async function status(deviceId) {
    const reason = await unavailableReason();
    const used = await store.get(`voice_day/${day()}/${deviceId}`);
    return { available: reason === null, reason, remainingChars: Math.max(0, config.deviceDailyChars - (used?.chars ?? 0)) };
  }

  /** Devuelve el audio MP3 de [text], o lanza un ServiceError que la app entiende como "usa la voz del dispositivo". */
  async function speak(deviceId, rawText, rawLanguage) {
    const text = String(rawText ?? "").trim();
    if (!text) throw new ServiceError(400, "invalid_input", "Texto vacío");
    if (text.length > MAX_TEXT) throw new ServiceError(413, "too_large", "Texto demasiado largo");
    const language = /^[a-z]{2}$/.test(String(rawLanguage ?? "")) ? String(rawLanguage) : undefined;

    const reason = await unavailableReason();
    if (reason) throw new ServiceError(503, "unavailable", "Voz natural no disponible", { reason });
    const deviceUse = await store.get(`voice_day/${day()}/${deviceId}`);
    if ((deviceUse?.chars ?? 0) + text.length > config.deviceDailyChars) {
      throw new ServiceError(429, "daily_limit", "Límite diario de voz natural alcanzado");
    }

    let response;
    try {
      response = await fetchImpl(`${API}${encodeURIComponent(config.voiceId)}?output_format=${config.outputFormat}`, {
        method: "POST",
        headers: { "xi-api-key": config.apiKey, "content-type": "application/json", accept: "audio/mpeg" },
        body: JSON.stringify({ text, model_id: config.model, ...(language ? { language_code: language } : {}) }),
        signal: AbortSignal.timeout(20_000),
      });
    } catch {
      throw new ServiceError(502, "upstream", "No se pudo contactar a ElevenLabs");
    }
    if (!response.ok) throw await translateError(response);

    const audio = Buffer.from(await response.arrayBuffer());
    await store.increment(`voice_day/${day()}/${deviceId}`, { chars: text.length });
    await store.increment(`voice_month/${month()}`, { chars: text.length, calls: 1 });
    return audio;
  }

  async function translateError(response) {
    const body = await response.text().catch(() => "");
    const outOfCredits = response.status === 402 || /quota_exceeded|insufficient|credits/i.test(body);
    if (outOfCredits) {
      // Sin créditos: se apaga un rato para no insistir; la app usa la voz del dispositivo.
      await store.set("voice_control", { suspendedUntil: now().getTime() + config.suspendHours * 3_600_000 });
      return new ServiceError(503, "unavailable", "Voz natural no disponible", { reason: "suspended" });
    }
    if (response.status === 401 || response.status === 403) {
      console.error("ElevenLabs rechazó la clave:", body.slice(0, 200));
      return new ServiceError(503, "unavailable", "Voz natural no disponible", { reason: "misconfigured" });
    }
    if (response.status === 429) return new ServiceError(429, "busy", "Voz natural ocupada");
    console.error("Error de ElevenLabs:", response.status, body.slice(0, 200));
    return new ServiceError(502, "upstream", "Error de la voz natural");
  }

  return { status, speak };
}
