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
   *
   * Los cupos se RESERVAN con un incremento atómico y se comprueba el total que devuelve: dos peticiones
   * simultáneas no pueden pasar las dos con el último cupo (leer, comparar y sumar por separado sí lo
   * permitía). Una reserva rechazada no se devuelve: restarla exigiría leer y escribir sin atomicidad.
   * Consecuencias, a propósito: los intentos por encima del límite siguen contando para esa tablet ese día,
   * y un intento rechazado por el tope global gasta también un cupo de la tablet. El contador global puede
   * quedar por encima del tope; por eso el número de reporte solo se usa si no lo supera.
   */
  async function submit(deviceId, channel, rawInput) {
    const parsed = reportInput.safeParse(rawInput);
    if (!parsed.success) throw new ServiceError(400, "invalid_input", "Datos no válidos");
    const day = dayKey(now());
    const device = await store.increment(`aventuras_report_use/${day}/${deviceId}`, { total: 1 });
    if (device.total > config.aventurasReportDeviceLimit) throw new ServiceError(429, "daily_limit", "Límite diario de reportes alcanzado");
    const { total } = await store.increment(`aventuras_report_day/${day}`, { total: 1 });
    if (total > config.aventurasReportDailyLimit) throw new ServiceError(429, "daily_limit", "Límite diario de reportes alcanzado");

    // [total] es único para este reporte (lo dio el incremento atómico) y no pasa del tope.
    const report = { at: now().toISOString(), channel, ...parsed.data };
    await store.set(`aventuras_report/${day}/${total}`, report);
    // Aviso en el registro del servidor: es lo que ve quien mantiene el servicio sin abrir nada más.
    log("Reporte de contenido del tutor de Aventuras:", JSON.stringify({ day, number: total, reason: report.reason, app: report.app }));
    return { day, number: total };
  }

  /** Reportes de un día (para revisarlos). */
  async function list(day) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new ServiceError(400, "invalid_input", "Día no válido");
    // El contador del día es el índice: se conserva los mismos 90 días que los reportes. Puede superar el
    // tope (reservas rechazadas); los reportes guardados van del 1 al tope como mucho.
    const reserved = (await store.get(`aventuras_report_day/${day}`))?.total ?? 0;
    const count = Math.min(reserved, config.aventurasReportDailyLimit);
    const reports = [];
    for (let n = 1; n <= count; n++) {
      const report = await store.get(`aventuras_report/${day}/${n}`);
      if (report) reports.push({ number: n, ...report });
    }
    return { day, count, reports };
  }

  return { submit, list };
}
