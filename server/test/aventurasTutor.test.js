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
