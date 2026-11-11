// Reportes de contenido del tutor de CREA Aventuras.
//
// Google Play pide que las apps que muestran contenido generado con IA dejen reportarlo a los
// desarrolladores sin salir de la app. La ruta del tutor la escribe Claude (un resumen y tres motivos),
// así que la app ofrece «Reportar esta sugerencia» y envía aquí el texto reportado.
//
// Minimización: el reporte lleva solo el texto que escribió la IA, las actividades elegidas, el motivo
// que marcó la persona y la versión de la app. No lleva perfil, apodo ni resultados, y el código de la
// tablet (X-Device-Id) se usa únicamente para el límite diario: no se guarda con el reporte.
import { z } from "zod";

export const REPORT_REASONS = ["ofensivo", "inadecuado_edad", "confuso_o_erroneo"];

export const reportInput = z.strictObject({
  version: z.literal(1),
  reason: z.enum(REPORT_REASONS),
  summary: z.string().min(1).max(400),
  choices: z.array(z.strictObject({ id: z.string().regex(/^[A-Z_]{3,20}:[A-Z_]{3,20}:[1-4]$/), reason: z.string().min(1).max(300) })).min(1).max(3),
  app: z.string().regex(/^[0-9A-Za-z.\-]{1,40}$/),
});

const dayKey = (now) => now.toISOString().slice(0, 10);

export function createReports({ config, store, ServiceError, now = () => new Date(), log = console.warn }) {
  /**
   * Guarda un reporte. [channel] es "app" (tablets sin CREA Home) o "service" (CREA OS). Límites: por
   * tablet y día, y un tope diario para todo el servicio (no se puede llenar el almacén a base de reportes).
   */
  async function submit(deviceId, channel, rawInput) {
    const parsed = reportInput.safeParse(rawInput);
    if (!parsed.success) throw new ServiceError(400, "invalid_input", "Datos no válidos");
    const day = dayKey(now());
    const perDevice = await store.get(`aventuras_report_use/${day}/${deviceId}`);
    if ((perDevice?.total ?? 0) >= config.aventurasReportDeviceLimit) throw new ServiceError(429, "daily_limit", "Límite diario de reportes alcanzado");
    const today = await store.get(`aventuras_report_day/${day}`);
    if ((today?.total ?? 0) >= config.aventurasReportDailyLimit) throw new ServiceError(429, "daily_limit", "Límite diario de reportes alcanzado");

    const { total } = await store.increment(`aventuras_report_day/${day}`, { total: 1 });
    await store.increment(`aventuras_report_use/${day}/${deviceId}`, { total: 1 });
    const report = { at: now().toISOString(), channel, ...parsed.data };
    await store.set(`aventuras_report/${day}/${total}`, report);
    // Aviso en el registro del servidor: es lo que ve quien mantiene el servicio sin abrir nada más.
    log("Reporte de contenido del tutor de Aventuras:", JSON.stringify({ day, number: total, reason: report.reason, app: report.app }));
    return { day, number: total };
  }

  /** Reportes de un día (para revisarlos). */
  async function list(day) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new ServiceError(400, "invalid_input", "Día no válido");
    const count = (await store.get(`aventuras_report_day/${day}`))?.total ?? 0;
    const reports = [];
    for (let n = 1; n <= Math.min(count, config.aventurasReportDailyLimit); n++) {
      const report = await store.get(`aventuras_report/${day}/${n}`);
      if (report) reports.push({ number: n, ...report });
    }
    return { day, count, reports };
  }

  return { submit, list };
}
