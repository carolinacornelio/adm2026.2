// Executar contra PosBrowserServer: API real e PostgreSQL descartável em 127.0.0.1:18765.
const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const baseURL = 'http://127.0.0.1:18765';
const gate = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };

(async () => {
    const api = await request.newContext({ baseURL });
    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    const erros = [];
    try {
        const cadastro = await api.post('/api/auth/cadastro', { data: {
            email: `revisao-${randomUUID()}@teste.invalid`, senha: 'teste-revisao-123', nome: 'Operador', empresa: 'Revisão', plano: 'PLUS',
            perguntaSeguranca1: 'AMIGO_INFANCIA', perguntaSeguranca2: 'EMPREGO_SONHOS', perguntaSeguranca3: 'CIDADE_PAIS',
            respostaSeguranca1: 'um', respostaSeguranca2: 'dois', respostaSeguranca3: 'tres',
        } });
        assert.equal(cadastro.status(), 200);
        const sessao = await cadastro.json(), headers = { Authorization: `Bearer ${sessao.token}` };
        async function post(url, data) {
            const r = await api.post(url, { data, headers });
            assert.ok(r.ok(), await r.text());
            return r.json();
        }
        const produto = await post('/api/produtos', { nome: 'Produto revisão', codigo: 'REV', precoCompra: 2, precoVenda: 5 });
        const fornecedor = await post('/api/pessoas', { nome: 'Fornecedor', tipo: 'FORNECEDOR' });
        const cliente = await post('/api/pessoas', { nome: 'Cliente', tipo: 'CLIENTE' });
        await post('/api/compras', { fornecedorId: fornecedor.id, itens: [{ produtoId: produto.id, quantidade: 10 }] });
        const contexto = await browser.newContext();
        await contexto.addInitScript(sessao => {
            if (location.hostname !== '127.0.0.1') return;
            sessionStorage.setItem('authToken', sessao.token);
            sessionStorage.setItem('usuario', JSON.stringify(sessao.usuario));
        }, sessao);
        contexto.on('page', p => p.on('pageerror', e => erros.push(e.message)));
        const page = await contexto.newPage();
        await page.goto(baseURL + '/vendas.html');
        await page.waitForFunction(() => !document.getElementById('btnFinalizarVenda').disabled);
        await page.locator('#selectCliente').selectOption(String(cliente.id));
        const linhas = page.locator('#corpoItensVenda tr');
        await linhas.nth(0).locator('[name=produto]').selectOption(String(produto.id));
        await linhas.nth(0).locator('[name=quantidade]').fill('6');
        await page.locator('#btnAdicionarItemVenda').click();
        await linhas.nth(1).locator('[name=quantidade]').fill('1');
        let envios = 0;
        const iniciou = gate(), liberar = gate();
        await page.route('**/api/vendas', async route => {
            envios++; iniciou.resolve(); await liberar.promise;
            await route.fulfill({ status: 500, json: { message: 'Falha simulada' } });
        });
        await page.locator('#btnFinalizarVenda').click();
        await page.getByText('Selecione um produto válido para cada item preenchido.', { exact: true }).waitFor();
        assert.equal(envios, 0);
        await linhas.nth(1).locator('[name=produto]').selectOption(String(produto.id));
        await linhas.nth(1).locator('[name=quantidade]').fill('6');
        await page.locator('#btnFinalizarVenda').click();
        await page.getByText(/Estoque insuficiente para/).waitFor();
        assert.equal(envios, 0);
        await linhas.nth(1).locator('[name=quantidade]').fill('1.5');
        await page.locator('#btnFinalizarVenda').click();
        await page.getByText('Informe quantidades inteiras entre 1 e 1.000.000.', { exact: true }).waitFor();
        assert.equal(envios, 0);
        await linhas.nth(1).locator('[name=quantidade]').fill('1');
        await page.evaluate(() => {
            document.getElementById('formVenda').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
            document.getElementById('btnFinalizarVenda').dispatchEvent(new MouseEvent('click', { bubbles: true }));
        });
        await iniciou.promise;
        liberar.resolve();
        await page.getByText('Falha simulada', { exact: true }).waitFor();
        assert.equal(envios, 1);
        assert.equal(await linhas.nth(0).locator('[name=quantidade]').inputValue(), '6');
        assert.equal(await linhas.nth(1).locator('[name=quantidade]').inputValue(), '1');
        console.log('PASS: venda não ignora linhas, soma produtos repetidos, rejeita frações, bloqueia envio simultâneo e mantém dados após falha.');

        await page.unroute('**/api/vendas');
        await page.route('**/api/produtos', route => route.fulfill({ status: 503, json: { message: 'Catálogo indisponível' } }));
        await page.goto(baseURL + '/vendas.html');
        await page.getByText('Catálogo indisponível', { exact: true }).waitFor();
        assert.equal(await page.locator('#btnFinalizarVenda').isDisabled(), true);
        await page.unroute('**/api/produtos');
        console.log('PASS: falha no carregamento de vendas mostra erro e impede envio incompleto.');

        await page.route('**/api/pos/eventos', route => route.abort());
        await page.goto(baseURL + '/produtos-listagem.html');
        await page.getByRole('cell', { name: 'Produto revisão', exact: true }).waitFor();
        let buscas = 0;
        await page.route('**/api/produtos', async route => { buscas++; await route.continue(); });
        await page.locator('#pesquisaProdutos').pressSequentially('Produto revisão', { delay: 15 });
        await page.waitForResponse(r => r.url().endsWith('/api/produtos'));
        await page.getByRole('cell', { name: 'Produto revisão', exact: true }).waitFor();
        assert.equal(buscas, 1, 'Digitação contínua deve produzir uma consulta, sem uma requisição por tecla.');
        await page.unroute('**/api/produtos');
        console.log('PASS: pesquisa de produtos agrupa a digitação em uma consulta.');

        await page.route('**/api/pos/eventos', route => route.abort());
        await page.goto(baseURL + '/pos.html');
        await page.locator(`#products [data-add="${produto.id}"]`).waitFor();
        await page.locator(`#products [data-add="${produto.id}"]`).click();
        await page.locator('#payment-method').selectOption('PIX');
        assert.equal(await page.locator('#paid-confirmation').isHidden(), true);
        await page.locator('#checkout').click();
        await page.locator('#confirm-dialog[open]').waitFor();
        await page.locator('#confirm-sale').click();
        await page.locator('#pix-dialog[open]').waitFor();
        assert.equal(await page.locator('#pix-dialog .pos-pix-qr').evaluate(img => img.complete && img.naturalWidth === 300), true);
        assert.equal((await page.locator('#pix-total').textContent()).replace(/\u00a0/g, ' '), 'R$ 5,00');
        let pagamentosPix = 0, payloadPix;
        await page.route('**/api/pos/vendas', async route => {
            pagamentosPix++;
            payloadPix = route.request().postDataJSON();
            await route.continue();
        });
        assert.equal(pagamentosPix, 0, 'A venda deve aguardar a confirmação manual após exibir o QR.');
        await page.locator('#pix-dialog [data-close]').click();
        assert.equal(await page.locator('#pix-dialog').evaluate(dialog => dialog.open), false);
        assert.equal(await page.locator('#cart tr').count(), 1);
        assert.equal(pagamentosPix, 0, 'Fechar o QR não registra a venda.');
        await page.locator('#checkout').click();
        await page.locator('#confirm-sale').click();
        await page.locator('#confirm-pix').click();
        await page.locator('#receipt-dialog[open]').waitFor();
        assert.equal(pagamentosPix, 1);
        assert.equal(payloadPix.formaPagamento, 'PIX');
        assert.equal(payloadPix.pagamentoConfirmado, true);
        assert.match(await page.locator('#receipt').innerText(), /Pagamento: Pix/);
        assert.match(await page.locator('#receipt').innerText(), /Recebimento confirmado pelo operador/);
        assert.equal((await (await api.get('/api/produtos', { headers })).json()).find(p => p.id === produto.id).quantidadeEstoque, 9);
        await page.unroute('**/api/pos/vendas');
        await page.unroute('**/api/pos/eventos');
        console.log('PASS: QR Pix aparece antes da gravação; cancelar não registra; confirmação manual grava uma venda e abre o comprovante.');

        const primeiroInventario = await post('/api/inventarios-fisicos', {});
        await page.goto(baseURL + '/estoque.html');
        const linha = page.locator('#listaItensInventarioFisico tr[data-item]').first();
        await linha.locator('.quantidade-contada').fill('8');
        const gravou = gate(), responder = gate();
        await page.route('**/api/inventarios-fisicos/*/itens/*', async route => {
            const response = await route.fetch();
            gravou.resolve(); await responder.promise;
            await route.fulfill({ response });
        });
        await linha.locator('.salvar-contagem').click();
        await gravou.promise;
        await linha.locator('.quantidade-contada').fill('9');
        responder.resolve();
        await page.getByText('Contagem registrada. Existem edições mais recentes ainda não salvas.', { exact: true }).waitFor();
        assert.equal(await linha.locator('.quantidade-contada').inputValue(), '9');
        assert.equal(await page.locator('#aprovarInventarioFisico').isDisabled(), true);
        await page.unroute('**/api/inventarios-fisicos/*/itens/*');
        await linha.locator('.salvar-contagem').click();
        await page.waitForFunction(() => !document.getElementById('aprovarInventarioFisico').disabled);
        console.log('PASS: edição durante gravação de inventário é preservada e exige nova confirmação.');

        const segundoInventario = await post('/api/inventarios-fisicos', {});
        await page.locator('#atualizarInventariosFisicos').click();
        await page.locator(`#seletorInventarioFisico option[value="${segundoInventario.id}"]`).waitFor({ state: 'attached' });
        await page.locator('#seletorInventarioFisico').selectOption(String(segundoInventario.id));
        await page.waitForFunction(id => document.getElementById('estadoInventarioFisico').textContent.startsWith(`Inventário #${id} ·`), segundoInventario.id);
        const carregouAntigo = gate(), liberarInventario = gate();
        await page.route(`**/api/inventarios-fisicos/${primeiroInventario.id}`, async route => {
            const response = await route.fetch();
            carregouAntigo.resolve(); await liberarInventario.promise;
            await route.fulfill({ response });
        });
        await page.locator('#seletorInventarioFisico').selectOption(String(primeiroInventario.id));
        await carregouAntigo.promise;
        await page.locator('#seletorInventarioFisico').selectOption(String(segundoInventario.id));
        await page.waitForFunction(id => document.getElementById('estadoInventarioFisico').textContent.startsWith(`Inventário #${id} ·`), segundoInventario.id);
        const respostaAntiga = page.waitForResponse(r => r.url().endsWith(`/api/inventarios-fisicos/${primeiroInventario.id}`));
        liberarInventario.resolve(); await respostaAntiga;
        await page.evaluate(() => new Promise(r => setTimeout(r, 100)));
        assert.ok((await page.locator('#estadoInventarioFisico').textContent()).startsWith(`Inventário #${segundoInventario.id} ·`));
        console.log('PASS: troca rápida de inventário mantém a contagem selecionada.');

        const janeiro = gate(), liberarJaneiro = gate();
        await page.route('**/api/dashboard/faturamento*', async route => {
            const mes = new URL(route.request().url()).searchParams.get('mesReferencia');
            if (mes === '2026-01') { janeiro.resolve(); await liberarJaneiro.promise; }
            await route.fulfill({ json: { mesAtualLabel: mes, mesAnteriorLabel: 'Anterior', faturamentoAtual: 10,
                faturamentoAnterior: 5, variacaoPercentual: 100 } });
        });
        await page.goto(baseURL + '/dashboard.html');
        await page.locator('#mesReferencia').fill('2026-01');
        await page.locator('#mesReferencia').dispatchEvent('change');
        await janeiro.promise;
        await page.locator('#mesReferencia').fill('2026-02');
        await page.locator('#mesReferencia').dispatchEvent('change');
        await page.waitForFunction(() => document.getElementById('mesAtualLabel').textContent === '2026-02');
        liberarJaneiro.resolve();
        await page.waitForResponse(r => r.url().includes('mesReferencia=2026-01'));
        await page.evaluate(() => new Promise(r => setTimeout(r, 100)));
        assert.equal(await page.locator('#mesAtualLabel').textContent(), '2026-02');
        console.log('PASS: resposta atrasada de faturamento não substitui o mês selecionado.');

        const encerramentoStream = await page.evaluate(async () => {
            const { apiService } = await import('/erp-js/apiService.js');
            const fetchOriginal = window.fetch;
            let cancelado = false;
            const stream = new ReadableStream({
                start(controller) { controller.enqueue(new TextEncoder().encode('event: conectado\ndata: {}\n\n')); },
                cancel() { cancelado = true; },
            });
            window.fetch = async () => new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } });
            try {
                await apiService.events('/revisao-stream', { onEvent() { throw new Error('Falha do consumidor'); } });
                return { cancelado, erro: null };
            } catch (erro) { return { cancelado, erro: erro.message }; }
            finally { window.fetch = fetchOriginal; }
        });
        assert.deepEqual(encerramentoStream, { cancelado: true, erro: 'Falha do consumidor' });
        console.log('PASS: falha ao tratar um evento encerra a conexão em tempo real.');

        const antiga = gate(), liberarAntiga = gate();
        await page.route('**/api/revisao-sessao', async route => {
            antiga.resolve(); await liberarAntiga.promise;
            await route.fulfill({ status: 401, json: { message: 'Sessão antiga expirada' } });
        });
        await page.evaluate(async () => {
            const { apiService } = await import('/erp-js/apiService.js');
            window.requisicaoAntiga = apiService.get('/revisao-sessao').catch(e => e.status);
        });
        await antiga.promise;
        await page.evaluate(() => sessionStorage.setItem('authToken', 'sessao-mais-recente'));
        liberarAntiga.resolve();
        assert.equal(await page.evaluate(() => window.requisicaoAntiga), 401);
        assert.equal(await page.evaluate(() => sessionStorage.getItem('authToken')), 'sessao-mais-recente');
        assert.ok(page.url().endsWith('/dashboard.html'));
        assert.deepEqual(erros, []);
        console.log('PASS: resposta 401 de requisição antiga mantém a sessão nova; sem erros JavaScript.');
        await contexto.close();
    } finally { await browser.close(); await api.dispose(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
