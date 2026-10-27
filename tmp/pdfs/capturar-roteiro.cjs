const {chromium,request}=require('C:/Users/aryel/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('fs'),path=require('path'),assert=require('assert/strict'),{randomUUID}=require('crypto');
const baseURL='http://127.0.0.1:18765',out=path.resolve('output/roteiro-rodada-2/prints');
const dia=n=>{const d=new Date();d.setDate(d.getDate()+n);return new Intl.DateTimeFormat('en-CA',{timeZone:'America/Fortaleza'}).format(d)};
(async()=>{
const api=await request.newContext({baseURL});
const reg=await api.post('/api/auth/cadastro',{data:{email:`video-${randomUUID()}@teste.local`,senha:'demo-roteiro-123',nome:'Equipe C',empresa:'RyuTech - demonstracao',perguntaSeguranca1:'AMIGO_INFANCIA',perguntaSeguranca2:'EMPREGO_SONHOS',perguntaSeguranca3:'CIDADE_PAIS',respostaSeguranca1:'um',respostaSeguranca2:'dois',respostaSeguranca3:'tres'}});assert.equal(reg.status(),200);
const s=await reg.json(),headers={Authorization:`Bearer ${s.token}`};
async function call(m,u,data){const r=await api[m](u,{headers,data});assert.ok(r.ok(),await r.text());return (r.headers()['content-type']||'').includes('json')?r.json():null}
const leite=await call('post','/api/produtos',{nome:'Leite integral 1 L',codigo:'LEITE',precoCompra:3,precoVenda:5.5,controlaValidade:true});
const zero=await call('post','/api/produtos',{nome:'Arroz branco 1 kg',codigo:'ARROZ',precoCompra:4,precoVenda:7});
const vencido=await call('post','/api/produtos',{nome:'Iogurte natural - lote vencido',codigo:'IOGURTE',precoCompra:2,precoVenda:4});
const f=await call('post','/api/pessoas',{nome:'Fornecedor demonstracao',tipo:'FORNECEDOR'});
await call('post','/api/compras',{fornecedorId:f.fornecedorId,itens:[{produtoId:vencido.id,quantidade:2,lote:'IOG-ANTIGO'}]});
const l=(await call('get','/api/inventario/lotes')).find(x=>x.produtoId===vencido.id);
await call('put',`/api/inventario/lotes/${l.id}/validade`,{lote:l.lote,validade:dia(-1)});
const browser=await chromium.launch({channel:'msedge',headless:true});
try{
const c=await browser.newContext({viewport:{width:1440,height:1250},timezoneId:'America/Fortaleza'});
await c.addInitScript(s=>{if(location.hostname==='127.0.0.1'){sessionStorage.setItem('authToken',s.token);sessionStorage.setItem('usuario',JSON.stringify(s.usuario))}},s);
const page=await c.newPage();await page.goto(baseURL+'/compras.html');await page.waitForFunction(()=>document.querySelector('#corpoItensCompra select').options.length>1);
await page.selectOption('#selectFornecedor',String(f.fornecedorId));
const a=page.locator('#corpoItensCompra tr').first();await a.locator('[name=produto]').selectOption(String(leite.id));await a.locator('[name=quantidade]').fill('4');await a.locator('[name=lote]').fill('LEITE-A');await a.locator('[name=validade]').fill(dia(5));
await page.click('#btnAdicionarItemCompra');const b=page.locator('#corpoItensCompra tr').nth(1);await b.locator('[name=produto]').selectOption(String(leite.id));await b.locator('[name=quantidade]').fill('6');await b.locator('[name=lote]').fill('LEITE-B');await b.locator('[name=validade]').fill(dia(45));
await page.screenshot({path:path.join(out,'01-entrada-com-lote-e-validade.png')});await page.click('#btnFinalizarCompra');await page.waitForURL('**/dashboard.html');
await page.goto(baseURL+'/estoque.html');await page.waitForFunction(()=>document.querySelector('#listaLotes').textContent.includes('LEITE-B'));
await page.screenshot({path:path.join(out,'02-estoque-antes-10-unidades.png')});
const pos=await c.newPage();await pos.goto(baseURL+'/pos.html');await pos.waitForFunction(()=>document.querySelector('#sync-status').textContent.includes('Conectado'));
await pos.locator(`[data-add="${leite.id}"]`).click();await pos.locator(`[data-add="${leite.id}"]`).click();await pos.fill('#received','20');await pos.check('#paid');
await pos.screenshot({path:path.join(out,'03-pos-venda-de-2-unidades.png')});await pos.click('#checkout');await pos.locator('#confirm-dialog[open]').waitFor();
const resposta=pos.waitForResponse(r=>r.url().endsWith('/api/pos/vendas')&&r.request().method()==='POST');await pos.click('#confirm-sale');const r=await resposta;assert.ok(r.ok(),await r.text());const inicio=performance.now();
await page.waitForFunction(()=>[...document.querySelectorAll('#listaEstoque tr')].some(tr=>tr.cells[0].textContent==='Leite integral 1 L'&&tr.cells[1].textContent==='8'),null,{timeout:5000});const latencia=Math.round(performance.now()-inicio);
await page.screenshot({path:path.join(out,'04-estoque-depois-8-unidades.png')});
const lotes=await call('get','/api/inventario/lotes');assert.equal(Number(lotes.find(x=>x.produtoId===leite.id&&x.lote==='LEITE-A').quantidade),2);assert.equal(Number(lotes.find(x=>x.produtoId===leite.id&&x.lote==='LEITE-B').quantidade),6);
await page.locator('#relatorioInventario').screenshot({path:path.join(out,'06-relatorio-validade-e-exportacao.png')});
const dl=page.waitForEvent('download');await page.click('#exportarInventario');await (await dl).saveAs(path.resolve('output/roteiro-rodada-2/inventario-demonstracao.csv'));
await pos.click('#new-sale');await pos.fill('#search','ARROZ');await pos.locator('#search-form').evaluate(f=>f.requestSubmit());await pos.locator('#message').filter({hasText:'estoque insuficiente'}).waitFor();
await pos.screenshot({path:path.join(out,'05-bloqueio-estoque-zerado.png')});
const negada=await api.post('/api/pos/vendas',{headers,data:{chaveOperacao:randomUUID(),itens:[{produtoId:zero.id,quantidade:1}],desconto:0,totalEsperado:7,formaPagamento:'DINHEIRO',valorRecebido:10,pagamentoConfirmado:true}});assert.equal(negada.status(),400);
const resultado={data:new Date().toISOString(),ambiente:'PostgreSQL temporario, API real, Edge headless',vendaPelaInterface:true,estoqueAntes:10,quantidadeVendida:2,estoqueDepois:8,fefo:{loteA:2,loteB:6},atualizacaoVisualAposRespostaMs:latencia,estoqueZeroStatus:negada.status(),mensagemApi:await negada.json(),csvExportado:true};
fs.writeFileSync(path.resolve('output/roteiro-rodada-2/validacao-demonstracao.json'),JSON.stringify(resultado,null,2));console.log(JSON.stringify(resultado));
}finally{await browser.close();await api.dispose()}
})().catch(e=>{console.error(e);process.exitCode=1});
