import assert from "node:assert/strict";
import { test } from "node:test";
import { aventurasTutor } from "../src/aventurasTutor.js";
import { createServer } from "../src/index.js";
import { createService, loadConfig } from "../src/service.js";
import { MemoryStore, ttlFor, createRedisStore } from "../src/store.js";
import { createReports } from "../src/aventurasReport.js";

const input = { version: 1, evaluated: true, candidates: Object.entries({silabas:"SILABAS",rimas:"RIMAS",matematicas:"MATEMATICAS"}).map(([game,id]) => ({id:`${id}:INICIAL:1`,game,stage:"inicial",level:1,objective:"Practicar.",reason:"Evaluación inicial.",recentAttempts:0,recentPercent:-1})) };
const plan = {summary:"Practicaremos lectura y números.",choices:input.candidates.map(c=>({id:c.id,reason:"Practicar esta habilidad."}))};
const user={email:"servicio@creanicaragua.org"};
function service(result=plan) {
  const config=loadConfig({CREA_AVENTURAS_SERVICE_TOKEN:"service-test",AUTH_MODE:"google"});
  const calls=[];
  const claude={messages:{parse:async params=>{calls.push(params);return {stop_reason:"end_turn",parsed_output:result,usage:{input_tokens:100,output_tokens:100}};}}};
  return {config,calls,service:createService({config,store:new MemoryStore(),claude})};
}
test("el contrato rechaza identidad, niveles inventados y ids falsos",()=>{
  assert.equal(aventurasTutor.input.safeParse(input).success,true);
  assert.equal(aventurasTutor.input.safeParse({...input,nickname:"Ana"}).success,false);
  const bad=structuredClone(input);bad.candidates[0].level=4;
  assert.equal(aventurasTutor.input.safeParse(bad).success,false);
  bad.candidates[0].level=1;bad.candidates[0].id="COLORES:SECUNDARIA:3";
  assert.equal(aventurasTutor.input.safeParse(bad).success,false);
});
test("Claude recibe actividades permitidas y produce una ruta con ambas areas",async()=>{
  const s=service();const response=await s.service.ask(user,"dev","aventuras_tutor",input);
  assert.deepEqual(response.result,plan);assert.ok(s.calls[0].output_config.format);
  assert.equal(s.calls[0].messages[0].content.includes("nickname"),false);
});
test("se rechazan ids inventados, repetidos y ausencia de matematicas",async()=>{
  for(const id of ["COLORES:SECUNDARIA:3","SILABAS:INICIAL:1","RIMAS:INICIAL:1"]){
    const p=structuredClone(plan);p.choices[2].id=id;
    await assert.rejects(service(p).service.ask(user,"dev","aventuras_tutor",input),{code:"bad_output"});
  }
});
test("acceso entre servidores exige su propio token y no depende de una sesion Google",async t=>{
  const s=service();const server=createServer({...s,authenticate:async()=>null});
  await new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));
  t.after(()=>new Promise(resolve=>server.close(resolve)));
  const url=`http://127.0.0.1:${server.address().port}/v1/aventuras/tutor`;
  const options={method:"POST",headers:{"content-type":"application/json","x-device-id":"a".repeat(64)},body:JSON.stringify(input)};
  assert.equal((await fetch(url,options)).status,401);
  const response=await fetch(url,{...options,headers:{...options.headers,authorization:"Bearer service-test"}});
  assert.equal(response.status,200);assert.deepEqual((await response.json()).result,plan);
  assert.equal(s.calls.length,1);
});
test("el tope diario de Aventuras es propio: no se agota con el limite por persona de 300",async()=>{
  const config=loadConfig({CREA_AVENTURAS_SERVICE_TOKEN:"service-test",AUTH_MODE:"google",USER_DAILY_LIMIT:"300",AVENTURAS_DAILY_LIMIT:"400"});
  const claude={messages:{parse:async()=>({stop_reason:"end_turn",parsed_output:plan,usage:{input_tokens:1,output_tokens:1}})}};
  const s=createService({config,store:new MemoryStore(),claude});
  const servicio={email:"aventuras-service@creanicaragua.org"};
  let ok=0;
  // 20 tablets x 20 peticiones: cada una por debajo de su limite de 30, 400 en total.
  for(let d=0;d<20;d++)for(let i=0;i<20;i++){await s.ask(servicio,"aventuras-"+d,"aventuras_tutor",input,{dailyLimit:config.aventurasDailyLimit});ok++;}
  assert.equal(ok,400);
  await assert.rejects(s.ask(servicio,"aventuras-nueva","aventuras_tutor",input,{dailyLimit:config.aventurasDailyLimit}),{status:429,code:"daily_limit"});
});
test("sin tope propio el limite por persona sigue aplicandose a las cuentas normales",async()=>{
  const config=loadConfig({AUTH_MODE:"google",USER_DAILY_LIMIT:"2"});
  const claude={messages:{parse:async()=>({stop_reason:"end_turn",parsed_output:plan,usage:{input_tokens:1,output_tokens:1}})}};
  const s=createService({config,store:new MemoryStore(),claude});
  await s.ask(user,"a","aventuras_tutor",input);await s.ask(user,"b","aventuras_tutor",input);
  await assert.rejects(s.ask(user,"c","aventuras_tutor",input),{status:429});
});

function listen(t, server) {
  return new Promise(resolve => server.listen(0, "127.0.0.1", resolve)).then(() => {
    t.after(() => new Promise(resolve => server.close(resolve)));
    return `http://127.0.0.1:${server.address().port}/v1/aventuras/tutor`;
  });
}
const post = (url, token, device = "b".repeat(64)) => fetch(url, {
  method: "POST",
  headers: { "content-type": "application/json", "x-device-id": device, ...(token ? { authorization: "Bearer " + token } : {}) },
  body: JSON.stringify(input),
});
function appService(env = {}) {
  const config = loadConfig({ CREA_AVENTURAS_SERVICE_TOKEN: "service-test", CREA_AVENTURAS_APP_TOKEN: "app-test", AUTH_MODE: "google", ...env });
  const claude = { messages: { parse: async () => ({ stop_reason: "end_turn", parsed_output: plan, usage: { input_tokens: 1, output_tokens: 1 } }) } };
  const store = new MemoryStore();
  return { config, store, service: createService({ config, store, claude }) };
}
test("tablets sin CREA Home entran con la llave de la app; las otras llaves siguen igual", async t => {
  const s = appService();
  const url = await listen(t, createServer({ ...s, authenticate: async () => null }));
  assert.equal((await post(url, null)).status, 401);
  assert.equal((await post(url, "otra")).status, 401);
  assert.equal((await post(url, "")).status, 401);
  const direct = await post(url, "app-test");
  assert.equal(direct.status, 200);
  assert.deepEqual((await direct.json()).result, plan);
  assert.equal((await post(url, "service-test")).status, 200);
});
test("la llave de la app tiene identidad y contadores propios", async t => {
  const s = appService({ AVENTURAS_APP_DAILY_LIMIT: "3", AVENTURAS_DAILY_LIMIT: "5000" });
  const url = await listen(t, createServer({ ...s, authenticate: async () => null }));
  // Tres consultas directas desde tablets distintas agotan el tope de la app...
  for (const d of ["1", "2", "3"]) assert.equal((await post(url, "app-test", d.repeat(64))).status, 200);
  const blocked = await post(url, "app-test", "4".repeat(64));
  assert.equal(blocked.status, 429);
  // ...pero CREA OS (llave de servicio) sigue funcionando, incluso para la misma tablet.
  assert.equal((await post(url, "service-test", "1".repeat(64))).status, 200);
});
test("solo con la llave de la app configurada, la de servicio no entra con valor vacio", async t => {
  const s = appService({ CREA_AVENTURAS_SERVICE_TOKEN: "" });
  const url = await listen(t, createServer({ ...s, authenticate: async () => null }));
  assert.equal((await post(url, "")).status, 401);
  assert.equal((await post(url, "service-test")).status, 401);
  assert.equal((await post(url, "app-test")).status, 200);
});
test("sin ninguna llave el tutor queda apagado", async t => {
  const s = appService({ CREA_AVENTURAS_SERVICE_TOKEN: "", CREA_AVENTURAS_APP_TOKEN: "" });
  const url = await listen(t, createServer({ ...s, authenticate: async () => null }));
  assert.equal((await post(url, "app-test")).status, 503);
});

// ---------- Reportes de contenido del tutor ----------
const report = { version: 1, reason: "ofensivo", summary: "Texto que escribió la IA.", choices: [{ id: "SILABAS:INICIAL:1", reason: "Motivo que escribió la IA." }], app: "1.12.0-directo" };
function reportServer(env = {}) {
  const s = appService(env);
  const lines = [];
  const reports = createReports({ config: s.config, store: s.store, ServiceError: s.service.ServiceError, now: () => new Date("2026-11-11T15:00:00Z"), log: (...a) => lines.push(a.join(" ")) });
  return { ...s, reports, lines };
}
const postReport = (url, token, body = report, device = "c".repeat(64)) => fetch(url + "/report", {
  method: "POST",
  headers: { "content-type": "application/json", "x-device-id": device, ...(token ? { authorization: "Bearer " + token } : {}) },
  body: JSON.stringify(body),
});
test("un reporte se guarda sin el código de la tablet y se puede revisar con la sesión de CREA", async t => {
  const s = reportServer();
  const server = createServer({ ...s, authenticate: async request => request.headers.authorization === "Bearer google" ? { email: "docente@creanicaragua.org" } : null });
  const url = await listen(t, server);
  const response = await postReport(url, "app-test");
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, day: "2026-11-11", number: 1 });
  const stored = await s.store.get("aventuras_report/2026-11-11/1");
  assert.deepEqual(stored, { at: "2026-11-11T15:00:00.000Z", channel: "app", ...report });
  assert.equal(JSON.stringify([...s.store.data.entries()].filter(([k]) => k.startsWith("aventuras_report/"))).includes("c".repeat(64)), false);
  assert.equal(s.lines.length, 1);
  assert.equal(s.lines[0].includes("Texto que escribió"), false); // el registro no repite el texto
  // No gasta consultas del tutor.
  assert.equal(await s.store.get("use/2026-11-11/aventuras-app-" + "c".repeat(64)), null);
  // Lectura: solo con sesión de Google de CREA; las llaves de la app no sirven para leer.
  const reportsUrl = url + "/reports?day=2026-11-11";
  assert.equal((await fetch(reportsUrl)).status, 401);
  assert.equal((await fetch(reportsUrl, { headers: { authorization: "Bearer app-test" } })).status, 401);
  const listed = await (await fetch(reportsUrl, { headers: { authorization: "Bearer google" } })).json();
  assert.equal(listed.count, 1);
  assert.equal(listed.reports[0].summary, report.summary);
  assert.equal((await fetch(url + "/reports?day=hoy", { headers: { authorization: "Bearer google" } })).status, 400);
});
test("los reportes exigen llave y contrato estricto, sin identidad ni campos de más", async t => {
  const s = reportServer();
  const url = await listen(t, createServer({ ...s, authenticate: async () => null }));
  assert.equal((await postReport(url, null)).status, 401);
  assert.equal((await postReport(url, "otra")).status, 401);
  assert.equal((await postReport(url, "service-test")).status, 200);
  assert.equal((await s.store.get("aventuras_report/2026-11-11/1")).channel, "service");
  for (const bad of [
    { ...report, nickname: "Ana" },
    { ...report, reason: "otro" },
    { ...report, summary: "x".repeat(401) },
    { ...report, choices: [] },
    { ...report, choices: [{ id: "cualquier cosa", reason: "x" }] },
    { ...report, choices: [{ ...report.choices[0], profile: "p1" }] },
    { ...report, app: "<script>" },
    { ...report, version: 2 },
  ]) assert.equal((await postReport(url, "app-test", bad)).status, 400, JSON.stringify(bad).slice(0, 60));
  assert.equal((await postReport(url, "app-test", report, "no-es-un-hash")).status, 400);
});
test("límite de reportes por tablet y tope diario del servicio", async t => {
  const s = reportServer({ AVENTURAS_REPORT_DEVICE_LIMIT: "2", AVENTURAS_REPORT_DAILY_LIMIT: "3" });
  const url = await listen(t, createServer({ ...s, authenticate: async () => null }));
  assert.equal((await postReport(url, "app-test", report, "1".repeat(64))).status, 200);
  assert.equal((await postReport(url, "app-test", report, "1".repeat(64))).status, 200);
  assert.equal((await postReport(url, "app-test", report, "1".repeat(64))).status, 429);
  assert.equal((await postReport(url, "app-test", report, "2".repeat(64))).status, 200);
  assert.equal((await postReport(url, "app-test", report, "3".repeat(64))).status, 429);
  // El tutor sigue funcionando: los reportes no tocan sus contadores.
  assert.equal((await post(url, "app-test", "1".repeat(64))).status, 200);
});
test("sin el módulo de reportes la ruta responde 503 y el tutor no cambia", async t => {
  const s = appService();
  const url = await listen(t, createServer({ ...s, authenticate: async () => null }));
  assert.equal((await postReport(url, "app-test")).status, 503);
  assert.equal((await post(url, "app-test")).status, 200);
});
test("los reportes caducan a los 90 días y sus contadores a los 40, también al escribirlos en Redis", async () => {
  assert.equal(ttlFor("aventuras_report/2026-11-11/1"), 90 * 86400);
  assert.equal(ttlFor("aventuras_report_day/2026-11-11"), 40 * 86400);
  assert.equal(ttlFor("aventuras_report_use/2026-11-11/aventuras-app-x"), 40 * 86400);
  assert.equal(ttlFor("control"), null);
  const sent = [];
  const redis = createRedisStore({ url: "https://redis.invalid", token: "t", fetchImpl: async (_url, options) => { sent.push(JSON.parse(options.body)); return { ok: true, json: async () => JSON.parse(options.body).map(() => ({ result: "OK" })) }; } });
  await redis.set("aventuras_report/2026-11-11/1", report);
  assert.deepEqual(sent[0].at(-1), ["EXPIRE", "aventuras_report/2026-11-11/1", String(90 * 86400)]);
  await redis.set("control", { paused: true });
  assert.equal(sent[1].some(c => c[0] === "EXPIRE"), false);
});
