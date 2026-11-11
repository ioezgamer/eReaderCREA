import assert from "node:assert/strict";
import { test } from "node:test";
import { aventurasTutor } from "../src/aventurasTutor.js";
import { createServer } from "../src/index.js";
import { createService, loadConfig } from "../src/service.js";
import { MemoryStore } from "../src/store.js";

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
