// Executar somente contra PosBrowserServer, que usa PostgreSQL descartável.
const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');

(async () => {
  const baseURL = 'http://127.0.0.1:18765';
  const api = await request.newContext({ baseURL });
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const errors = [];
  try {
    const response = await api.post('/api/auth/cadastro', { data: {
      email: `modulos-${randomUUID()}@teste.local`, senha: 'senha-teste-123', nome: 'Operador', empresa: 'Novos módulos',
      perguntaSeguranca1: 'AMIGO_INFANCIA', perguntaSeguranca2: 'EMPREGO_SONHOS', perguntaSeguranca3: 'CIDADE_PAIS',
      respostaSeguranca1: 'um', respostaSeguranca2: 'dois', respostaSeguranca3: 'tres'
    }});
    assert.equal(response.status(), 200, await response.text());
    const session = await response.json();
    const headers = { Authorization: `Bearer ${session.token}` };
    const call = async (method, url, data) => {
      const r = await api[method](url, { headers, data });
      assert.ok(r.ok(), `${url}: ${r.status()} ${await r.text()}`);
      return r.json();
    };
    const p1 = await call('post', '/api/produtos', { nome: 'Produto A', codigo: 'A', precoCompra: 2, precoVenda: 5 });
    const p2 = await call('post', '/api/produtos', { nome: 'Produto B', codigo: 'B', precoCompra: 2, precoVenda: 5 });
    const fornecedor = await call('post', '/api/pessoas', { nome: 'Fornecedor', tipo: 'FORNECEDOR' });
    await call('post', '/api/compras', { fornecedorId: fornecedor.fornecedorId, itens: [
      { produtoId: p1.id, quantidade: 4, lote: 'A' }, { produtoId: p2.id, quantidade: 6, lote: 'B' }
    ]});
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'America/Fortaleza' });
    await context.addInitScript(s => {
      sessionStorage.setItem('authToken', s.token);
      sessionStorage.setItem('usuario', JSON.stringify(s.usuario));
    }, session);
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => d.accept());
    await page.goto(`${baseURL}/atendimento.html`);
    await page.locator('#cliente').fill('Cliente <teste>');
    await page.locator('#tituloChamado').fill('Verificar produto');
    await page.locator('#descricao').fill('Descrição com <script>texto</script> exibida como texto.');
    await page.getByRole('button', { name: 'Abrir chamado', exact: true }).click();
    await page.locator('#listaChamados button').waitFor();
    assert.equal(await page.locator('#listaChamados tr').count(), 1);
    await page.locator('#listaChamados button').click();
    assert.match(await page.locator('#descricaoDetalheChamado').textContent(), /<script>texto<\/script>/);
    await page.locator('#situacaoChamado').selectOption('Em atendimento');
    await page.getByRole('button', { name: 'Salvar situação', exact: true }).click();
    await page.locator('#detalheChamado').waitFor({ state: 'hidden' });
    assert.match(await page.locator('#listaChamados').textContent(), /Em atendimento/);
    fs.mkdirSync('target/validacao-modulos', { recursive: true });
    await page.screenshot({ path: path.resolve('target/validacao-modulos/atendimento.png'), fullPage: true });
    await page.locator('.menu a[href="estoque.html"]').click();
    await page.locator('#novoInventarioFisico').click();
    await page.locator('#listaItensInventarioFisico tr[data-item]').first().waitFor();
    const rows = page.locator('#listaItensInventarioFisico tr[data-item]');
    assert.equal(await rows.count(), 2);
    await rows.nth(0).locator('.quantidade-contada').fill('5');
    await rows.nth(1).locator('.quantidade-contada').fill('5');
    await rows.nth(0).locator('.salvar-contagem').click();
    await page.waitForFunction(() => document.querySelector('#resumoInventarioFisico').textContent.includes('1 / 2'));
    assert.equal(await rows.nth(1).locator('.quantidade-contada').inputValue(), '5');
    assert.equal(await page.locator('#aprovarInventarioFisico').isDisabled(), true);
    await rows.nth(1).locator('.salvar-contagem').click();
    await page.waitForFunction(() => !document.querySelector('#aprovarInventarioFisico').disabled);
    await page.locator('#aprovarInventarioFisico').click();
    await page.waitForFunction(() => document.querySelector('#aprovarInventarioFisico').textContent.includes('finalizado'));
    const produtos = await call('get', '/api/produtos');
    assert.ok(produtos.every(p => Number(p.quantidadeTotal) === 5));
    await page.screenshot({ path: path.resolve('target/validacao-modulos/inventario-fisico.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${baseURL}/atendimento.html`);
    await page.locator('#listaChamados button').waitFor();
    assert.equal(await page.locator('#formChamado').isVisible(), true);
    assert.deepEqual(errors, []);
    console.log('PASS: atendimento, navegação, situação, texto seguro, contagem, edições preservadas, aprovação, saldos e tela móvel.');
    await context.close();
  } finally {
    await browser.close();
    await api.dispose();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
