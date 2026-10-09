// La lógica del servicio, sin HTTP: se puede probar con un cliente de Claude y un almacén falsos.
import Anthropic from "@anthropic-ai/sdk";
import { zodOutputFormat } from "@anthropic-ai/sdk/helpers/zod";
import { TASKS, mockResult } from "./tasks.js";

// Precios por millón de tokens (entrada, salida). Se pueden sustituir con la variable PRICES (JSON).
const DEFAULT_PRICES = {
  "claude-haiku-5-5": [0.1, 0.5],
  "claude-sonnet-5-5": [2, 10],
  "claude-opus-5-5": [4, 20],
};

export function loadConfig(env = process.env) {
  const fast = env.MODEL_FAST || "claude-haiku-5-5";
  return {
    enabled: env.AI_ENABLED !== "false",
    mock: env.MOCK_AI === "1",
    authMode: env.AUTH_MODE === "mock" ? "mock" : "google",
    allowedDomains: (env.ALLOWED_DOMAINS || "creanicaragua.org").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean),
    allowedAudiences: (env.ALLOWED_AUDIENCES || "").split(",").map((s) => s.trim()).filter(Boolean),
    models: { fast, teacher: env.MODEL_TEACHER || fast },
    monthlyBudgetUsd: Number(env.MONTHLY_BUDGET_USD || 200),
    userDailyLimit: Number(env.USER_DAILY_LIMIT || 300),
    suspendHours: Number(env.SUSPEND_HOURS || 6),
    prices: { ...DEFAULT_PRICES, ...(env.PRICES ? JSON.parse(env.PRICES) : {}) },
  };
}

class ServiceError extends Error {
  constructor(status, code, message, extra = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

const dayKey = (now) => now.toISOString().slice(0, 10);
const monthKey = (now) => now.toISOString().slice(0, 7);

export function createService({ config, store, claude, now = () => new Date() }) {
  // Una clave sin workspace propio exige indicar en qué workspace se factura cada petición.
  const workspaceId = process.env.ANTHROPIC_WORKSPACE_ID;
  const client = claude ?? (config.mock ? null : new Anthropic(workspaceId ? { defaultHeaders: { "anthropic-workspace-id": workspaceId } } : {}));

  /** Si el servicio está apagado, sin créditos o sin presupuesto, dice por qué; si no, null. */
  async function unavailableReason() {
    if (!config.enabled) return "disabled";
    const control = await store.get("control");
    if (control?.enabled === false) return "disabled";
    if (control?.suspendedUntil && control.suspendedUntil > now().getTime()) return "suspended";
    const spend = await store.get(`spend/${monthKey(now())}`);
    if (((spend?.micros ?? 0) / 1_000_000) >= config.monthlyBudgetUsd) return "budget";
    return null;
  }

  async function status(user, deviceId) {
    const reason = await unavailableReason();
    const used = await store.get(`use/${dayKey(now())}/${deviceId}`);
    const remaining = {};
    for (const [name, task] of Object.entries(TASKS)) {
      remaining[name] = Math.max(0, task.limit.perDevice - (used?.[name] ?? 0));
    }
    return { available: reason === null, reason, tasks: Object.keys(TASKS), remaining };
  }

  async function ask(user, deviceId, taskName, rawInput) {
    const task = TASKS[taskName];
    if (!task) throw new ServiceError(400, "unknown_task", "Tarea desconocida");
    const parsed = task.input.safeParse(rawInput);
    if (!parsed.success) throw new ServiceError(400, "invalid_input", "Datos no válidos");
    const input = parsed.data;

    const reason = await unavailableReason();
    if (reason) throw new ServiceError(503, "unavailable", "Funciones de IA no disponibles", { reason });

    // Límites: por dispositivo y tarea, y por persona en total, cada día.
    const day = dayKey(now());
    const deviceUse = await store.get(`use/${day}/${deviceId}`);
    if ((deviceUse?.[taskName] ?? 0) >= task.limit.perDevice) {
      throw new ServiceError(429, "daily_limit", "Límite diario alcanzado para esta función");
    }
    const userUse = await store.get(`user/${day}/${user.email}`);
    if ((userUse?.total ?? 0) >= config.userDailyLimit) {
      throw new ServiceError(429, "daily_limit", "Límite diario alcanzado");
    }

    let result;
    let usage = { input_tokens: 0, output_tokens: 0 };
    let model = task.model === "teacher" ? config.models.teacher : config.models.fast;

    if (config.mock) {
      result = mockResult(taskName, input);
    } else {
      ({ result, usage } = await callClaude(client, task, model, input));
    }

    // Contabilidad después de una respuesta correcta.
    await store.increment(`use/${day}/${deviceId}`, { [taskName]: 1 });
    await store.increment(`user/${day}/${user.email}`, { total: 1 });
    const [priceIn, priceOut] = config.prices[model] ?? [1, 5];
    const micros = Math.round(usage.input_tokens * priceIn + usage.output_tokens * priceOut);
    await store.increment(`spend/${monthKey(now())}`, { micros, calls: 1 });

    return { result, model: config.mock ? "mock" : model };
  }

  async function callClaude(api, task, model, input) {
    const params = {
      model,
      max_tokens: task.maxTokens,
      system: task.system,
      messages: [{ role: "user", content: task.user(input) }],
    };
    // Haiku 5.5 piensa por defecto; para fichas y respuestas cortas se desactiva y se baja el esfuerzo.
    if (model.startsWith("claude-haiku-5")) params.thinking = { type: "disabled" };
    const outputConfig = { effort: "low" };

    try {
      let response;
      if (task.output) {
        response = await api.messages.parse({ ...params, output_config: { ...outputConfig, format: zodOutputFormat(task.output) } });
        if (response.stop_reason === "refusal") throw new ServiceError(422, "refused", "No se pudo procesar este contenido");
        if (!response.parsed_output) throw new ServiceError(502, "bad_output", "Respuesta no válida del modelo");
        return { result: response.parsed_output, usage: response.usage };
      }
      response = await api.messages.create({ ...params, output_config: outputConfig });
      if (response.stop_reason === "refusal") throw new ServiceError(422, "refused", "No se pudo procesar este contenido");
      const text = response.content.filter((block) => block.type === "text").map((block) => block.text).join("").trim();
      if (!text) throw new ServiceError(502, "bad_output", "Respuesta vacía del modelo");
      return { result: text, usage: response.usage };
    } catch (error) {
      if (error instanceof ServiceError) throw error;
      throw await translateApiError(error);
    }
  }

  async function translateApiError(error) {
    const status = error?.status;
    const text = String(error?.message ?? "");
    const outOfCredits = status === 402 || /credit balance|billing|insufficient/i.test(text);
    if (outOfCredits) {
      // Sin créditos: se apagan las funciones un rato para no seguir insistiendo; la app seguirá sin IA.
      await store.set("control", {
        ...(await store.get("control")),
        suspendedUntil: now().getTime() + config.suspendHours * 3_600_000,
      });
      return new ServiceError(503, "unavailable", "Funciones de IA no disponibles", { reason: "suspended" });
    }
    if (status === 429) return new ServiceError(429, "busy", "Servicio ocupado, intenta en un momento");
    if (status === 401 || status === 403) {
      console.error("La clave de Anthropic fue rechazada:", text);
      return new ServiceError(503, "unavailable", "Funciones de IA no disponibles", { reason: "misconfigured" });
    }
    console.error("Error de la API de Claude:", status, text);
    return new ServiceError(502, "upstream", "Error del servicio de IA");
  }

  return { status, ask, ServiceError };
}
