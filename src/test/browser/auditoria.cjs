// Usar exclusivamente o PosBrowserServer, com banco descartável em 127.0.0.1:18765.
const {chromium,request}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const {randomUUID}=require('node:crypto');
(async()=>{
  const baseURL='http://127.0.0.1:18765';
  const api=await request.newContext({baseURL});
  const browser=await chromium.launch({channel:'msedge',headless:true});
  const email=`auditoria-${randomUUID()}@teste.local`,senha='senha-teste-123';
  try {
    const registro=await api.post('/api/auth/cadastro',{data:{email,senha,nome:'Operador',empresa:'Auditoria',
      perguntaSeguranca1:'AMIGO_INFANCIA',perguntaSeguranca2:'EMPREGO_SONHOS',perguntaSeguranca3:'CIDADE_PAIS',
      respostaSeguranca1:'resposta um',respostaSeguranca2:'resposta dois',respostaSeguranca3:'resposta tres'}});
    assert.equal(registro.status(),200,await registro.text());
    const sessao=await registro.json(),headers={Authorization:`Bearer ${sessao.token}`};
    const context=await browser.newContext();
    const page=await context.newPage(),errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.goto(baseURL+'/login.html');
    const restaurar=async()=>page.evaluate(s=>{
      sessionStorage.setItem('authToken',s.token);sessionStorage.setItem('usuario',JSON.stringify(s.usuario));
    },sessao);
    await restaurar();await page.goto(baseURL+'/pessoas-cadastro.html');
    await page.locator('#formPessoa [name=nome]').fill('Pessoa da auditoria');
    await page.locator('#formPessoa [name=documento]').fill('DOC-AUDIT');
    await page.locator('#formPessoa [name=nome]').press('Enter');
    await page.waitForURL('**/pessoas-listagem.html');
    await page.getByRole('cell',{name:'Pessoa da auditoria',exact:true}).waitFor();
    await page.goto(baseURL+'/produtos-cadastro.html');
    await page.locator('#formProduto [name=nome]').fill('Produto da auditoria');
    await page.locator('#formProduto [name=codigo]').fill('AUDIT');
    await page.locator('#formProduto [name=precoCompra]').fill('2.50');
    await page.locator('#formProduto [name=precoVenda]').fill('5.00');
    await page.locator('#formProduto [name=estoqueMinimo]').fill('0');
    await page.locator('#formProduto [name=nome]').press('Enter');
    await page.waitForURL('**/produtos-listagem.html');
    await page.getByRole('cell',{name:'Produto da auditoria',exact:true}).waitFor();
    const produtos=await (await api.get('/api/produtos',{headers})).json();
    const fornecedor=await (await api.post('/api/pessoas',{headers,data:{nome:'Fornecedor',tipo:'FORNECEDOR'}})).json();
    const compra=await api.post('/api/compras',{headers,data:{fornecedorId:fornecedor.fornecedorId,itens:[{produtoId:produtos[0].id,quantidade:3,lote:'AUDIT-LOTE'}]}});
    assert.equal(compra.status(),200,await compra.text());
    await page.goto(baseURL+'/estoque.html');
    await page.getByRole('cell',{name:'AUDIT-LOTE',exact:true}).waitFor();
    const downloadPromise=page.waitForEvent('download');await page.locator('#exportarInventario').click();
    const download=await downloadPromise;
    const csv=await fs.readFile(await download.path(),'utf8');
    const linhas=csv.trim().split(/\r?\n/);
    assert.equal(linhas[0].split(';').length,8);
    assert.equal(linhas[1].split(';').length,8);
    assert.match(linhas[0],/Nível de alerta/);
    assert.equal(await page.locator('.topo .atalho-atendimento').count(),1);
    assert.equal(await page.locator('.menu a[href="atendimento.html"]').count(),0);
    await page.goto(baseURL+'/dashboard.html');
    await page.waitForFunction(()=>[...document.querySelectorAll('.icone-imagem')].every(i=>i.complete&&i.naturalWidth>0));
    assert.equal(await page.locator('.icone-imagem').count(),10);
    await page.evaluate(()=>sessionStorage.setItem('usuario','{invalido'));
    await page.goto(baseURL+'/dashboard.html');await page.waitForURL('**/login.html');
    await restaurar();await page.goto(baseURL+'/dashboard.html');
    await page.locator('[data-logout]').click();await page.waitForURL('**/login.html');
    assert.equal((await api.get('/api/produtos',{headers})).status(),401);
    await page.locator('#formLogin [name=email]').fill(email);
    await page.locator('#formLogin [name=senha]').fill(senha);
    await page.locator('#formLogin [type=submit]').click();
    await page.locator('#formSegundoFator [name=respostaSeguranca1]').fill('errada');
    await page.locator('#formSegundoFator [name=respostaSeguranca2]').fill('resposta dois');
    await page.locator('#formSegundoFator [name=respostaSeguranca3]').fill('resposta tres');
    await page.locator('#formSegundoFator [type=submit]').click();
    await page.getByText(/Restam 2 tentativa/).waitFor();
    await page.locator('#formSegundoFator [name=respostaSeguranca1]').fill('resposta um');
    await page.locator('#formSegundoFator [type=submit]').click();
    await page.waitForURL('**/selecionar-sistema.html');
    assert.deepEqual(errors,[]);
    console.log('PASS: cadastro com Enter, pessoas/produtos, CSV de 8 colunas, imagens locais, atalho de atendimento, sessão corrompida, logout e nova tentativa de segundo fator.');
    await context.close();
  } finally {await browser.close();await api.dispose();}
})().catch(e=>{console.error(e);process.exitCode=1;});
