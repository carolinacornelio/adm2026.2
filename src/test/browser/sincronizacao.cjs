// Executar somente contra PosBrowserServer: PostgreSQL descartável em 127.0.0.1:18765.
const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { readFileSync, writeFileSync } = require('node:fs');
const { resolve, extname } = require('node:path');
const http = require('node:http');
const baseURL = 'http://127.0.0.1:18765';

async function esperar(condicao, descricao, timeout = 4500) {
    const limite = Date.now() + timeout;
    while (Date.now() < limite) {
        if (await condicao()) return;
        await new Promise(r => setTimeout(r, 80));
    }
    throw new Error(`Tempo esgotado: ${descricao}`);
}

(async () => {
    const api = await request.newContext({ baseURL });
    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    const erros = [];
    let live;
    try {
        for (const arquivo of ['compras.js', 'produtos.js', 'estoque.js', 'estoqueSync.js']) {
            const servido = await api.get('/erp-js/' + arquivo);
            assert.equal(servido.status(), 200);
            assert.equal(await servido.text(), readFileSync(resolve('front-end-erp/erp-js', arquivo), 'utf8'), `Spring deve servir o arquivo atual: ${arquivo}`);
        }
        const registro = await api.post('/api/auth/cadastro', { data: {
            email: `sync-${randomUUID()}@teste.invalid`, senha: 'teste-sync-123', nome: 'Operador', empresa: 'Sincronização',
            perguntaSeguranca1: 'AMIGO_INFANCIA', perguntaSeguranca2: 'EMPREGO_SONHOS', perguntaSeguranca3: 'CIDADE_PAIS',
            respostaSeguranca1: 'um', respostaSeguranca2: 'dois', respostaSeguranca3: 'tres',
        } });
        assert.equal(registro.status(), 200, await registro.text());
        const sessao = await registro.json();
        const headers = { Authorization: `Bearer ${sessao.token}` };
        async function chamar(metodo, caminho, data) {
            const r = await api[metodo](caminho, { headers, data });
            assert.ok(r.ok(), `${caminho}: ${await r.text()}`);
            return r.json();
        }
        const perecivel = await chamar('post', '/api/produtos', { nome: 'Sync leite', codigo: 'SYNC-LEITE', precoCompra: 3, precoVenda: 5, controlaValidade: true });
        const opcional = await chamar('post', '/api/produtos', { nome: 'Sync arroz', codigo: 'SYNC-ARROZ', precoCompra: 5, precoVenda: 7 });
        const fornecedor = await chamar('post', '/api/pessoas', { nome: 'Fornecedor sync', tipo: 'FORNECEDOR' });
        const contexto = async (semBroadcast = false, sessaoContexto = sessao) => {
            const c = await browser.newContext({ timezoneId: 'America/Fortaleza' });
            await c.addInitScript(({ sessao, semBroadcast }) => {
                if (location.hostname !== '127.0.0.1') return;
                sessionStorage.setItem('authToken', sessao.token);
                sessionStorage.setItem('usuario', JSON.stringify(sessao.usuario));
                // Simula duas janelas visíveis lado a lado no cenário de BroadcastChannel.
                Object.defineProperty(document, 'hidden', { get: () => false });
                if (semBroadcast) Object.defineProperty(window, 'BroadcastChannel', { value: undefined });
            }, { sessao: sessaoContexto, semBroadcast });
            c.on('page', p => p.on('pageerror', e => erros.push(e.message)));
            return c;
        };
        const c = await contexto();
        const lista = await c.newPage();
        await lista.addInitScript(() => {
            const intervalo = window.setInterval.bind(window);
            window.setInterval = (fn, ms, ...args) => intervalo(fn, ms === 15000 ? 60000 : ms, ...args);
        });
        await lista.route('**/api/pos/eventos', r => r.abort()); // Isola o aviso entre abas.
        await lista.goto(baseURL + '/produtos-listagem.html');
        await lista.locator('#pesquisaProdutos').fill('Sync leite');
        const saldo = pagina => pagina.locator('#listaProdutos tr').first().locator('td').nth(4).evaluate(e => e.firstChild.textContent.trim());
        const saldoEstoque = pagina => pagina.locator('#listaEstoque tr').first().locator('td').nth(2).innerText().then(texto => texto.split(' · ')[0].trim());
        const intervaloConsulta = (pagina, ms) => pagina.addInitScript(ms => {
            const intervalo = window.setInterval.bind(window);
            window.setInterval = (fn, atraso, ...args) => intervalo(fn, atraso === 15000 ? ms : atraso, ...args);
        }, ms);
        const estoque = await c.newPage();
        await intervaloConsulta(estoque, 60000);
        await estoque.route('**/api/pos/eventos', r => r.abort());
        await estoque.goto(baseURL + '/estoque.html');
        await estoque.locator('#pesquisaEstoque').fill('Sync leite');
        await esperar(async () => await saldoEstoque(estoque) === '0', 'estoque inicial sem stream');
        await estoque.locator('#buscaLote').fill('LEITE');
        await estoque.locator('#situacaoLote').selectOption('Regular');
        await estoque.locator('#filtrosInventario button[type=submit]').click();
        await esperar(async () => await saldo(lista) === '0', 'saldo inicial');
        const compra = await c.newPage();
        await compra.goto(baseURL + '/compras.html');
        await compra.waitForFunction(() => !document.querySelector('#btnFinalizarCompra').disabled);
        await compra.selectOption('#selectFornecedor', String(fornecedor.fornecedorId));
        const linha = compra.locator('#corpoItensCompra tr').first();
        await linha.locator('[name=produto]').selectOption(String(opcional.id));
        await linha.locator('[name=quantidade]').fill('4');
        await linha.locator('[name=lote]').fill('OPCIONAL');
        await linha.locator('[name=validade]').fill('2099-12-31');
        assert.equal(await linha.locator('[name=lote]').isVisible(), true);
        await linha.locator('[data-remover-item]').click();
        assert.equal(await linha.locator('[name=lote]').inputValue(), '');
        assert.equal(await linha.locator('[name=validade]').inputValue(), '');
        assert.match(await compra.locator('#totalCompra').textContent(), /0,00/);
        await linha.locator('[name=produto]').selectOption(String(perecivel.id));
        assert.equal(await linha.locator('[name=validade]').getAttribute('required'), '');
        await linha.locator('[name=quantidade]').fill('2');
        await linha.locator('[name=quantidade]').press('Enter');
        const segunda = compra.locator('#corpoItensCompra tr').nth(1);
        assert.equal(await segunda.locator('[name=produto]').inputValue(), '');
        assert.equal(await segunda.locator('[name=lote]').inputValue(), '');
        assert.deepEqual(await compra.locator('#corpoItensCompra select').evaluateAll(l => l.map(s => s.options.length)), [3, 3]);
        await segunda.locator('[name=produto]').selectOption(String(opcional.id));
        await segunda.locator('[name=quantidade]').fill('4');
        await segunda.locator('[name=lote]').fill('OPCIONAL');
        await segunda.locator('[name=validade]').fill('2099-12-31');
        await linha.locator('[name=lote]').fill('LEITE');
        await linha.locator('[name=validade]').fill('2000-01-01');
        await compra.click('#btnFinalizarCompra');
        assert.match(await compra.locator('.mensagem-formulario').textContent(), /venceu/);
        assert.match(await compra.locator('#totalCompra').textContent(), /26,00/);
        await linha.locator('[name=validade]').fill('2099-12-31');
        let tentativas = 0;
        await compra.route('**/api/compras', async r => {
            if (r.request().method() !== 'POST') return r.continue();
            tentativas++;
            if (tentativas === 1) return r.fulfill({ status: 500, contentType: 'application/json', body: '{"message":"Falha simulada"}' });
            return r.continue();
        });
        await compra.click('#btnFinalizarCompra');
        await esperar(async () => (await compra.locator('.mensagem-formulario').textContent()).includes('Falha simulada'), 'erro da compra');
        assert.equal(await saldo(lista), '0');
        assert.equal(await linha.locator('[name=lote]').inputValue(), 'LEITE');
        const outroRegistro = await api.post('/api/auth/cadastro', { data: {
            email: `outro-${randomUUID()}@teste.invalid`, senha: 'teste-sync-123', nome: 'Outro', empresa: 'Outra empresa',
            perguntaSeguranca1: 'AMIGO_INFANCIA', perguntaSeguranca2: 'EMPREGO_SONHOS', perguntaSeguranca3: 'CIDADE_PAIS',
            respostaSeguranca1: 'um', respostaSeguranca2: 'dois', respostaSeguranca3: 'tres',
        } });
        assert.equal(outroRegistro.status(), 200);
        const outroContexto = await contexto(false, await outroRegistro.json());
        const outraEmpresa = await outroContexto.newPage();
        await outraEmpresa.goto(baseURL + '/produtos-listagem.html');
        await outraEmpresa.evaluate(async () => {
            window.avisosDeOutraEmpresa = 0;
            const { aoMovimentar } = await import('./erp-js/estoqueSync.js');
            window.cancelarProbe = aoMovimentar(() => window.avisosDeOutraEmpresa++);
        });
        const inicio = Date.now();
        await compra.evaluate(() => { const b = document.querySelector('#btnFinalizarCompra'); b.click(); b.click(); });
        await esperar(async () => await saldo(lista) === '2', 'BroadcastChannel atualiza produtos sem SSE');
        await esperar(async () => await saldoEstoque(estoque) === '2', 'BroadcastChannel atualiza estoque sem SSE');
        assert.equal(await estoque.locator('#pesquisaEstoque').inputValue(), 'Sync leite');
        assert.equal(await estoque.locator('#buscaLote').inputValue(), 'LEITE');
        assert.equal(await estoque.locator('#situacaoLote').inputValue(), 'Regular');
        await esperar(async () => (await estoque.locator('#listaLotes').innerText()).includes('LEITE'), 'lote da compra aparece no estoque');
        const latenciaBroadcastMs = Date.now() - inicio;
        await compra.waitForURL('**/dashboard.html', { waitUntil: 'domcontentloaded' });
        assert.equal(tentativas, 2, 'clique duplicado não pode enviar duas compras');
        assert.equal(await outraEmpresa.evaluate(() => window.avisosDeOutraEmpresa), 0, 'aviso entre abas deve ficar na empresa correta');
        await outraEmpresa.evaluate(() => window.cancelarProbe());
        await outroContexto.close();
        assert.equal(await lista.locator('#pesquisaProdutos').inputValue(), 'Sync leite');
        const lotes = await chamar('get', '/api/inventario/lotes');
        assert.ok(lotes.some(l => l.lote === 'OPCIONAL' && Number(l.quantidade) === 4));

        // Outro contexto equivale a outro dispositivo e não compartilha BroadcastChannel.
        const remoto = await contexto(true);
        const listaRemota = await remoto.newPage();
        await listaRemota.goto(baseURL + '/produtos-listagem.html');
        await listaRemota.locator('#pesquisaProdutos').fill('Sync leite');
        await esperar(async () => await saldo(listaRemota) === '2', 'saldo remoto inicial');
        const estoqueRemoto = await remoto.newPage();
        await estoqueRemoto.goto(baseURL + '/estoque.html');
        await estoqueRemoto.locator('#pesquisaEstoque').fill('Sync leite');
        await esperar(async () => await saldoEstoque(estoqueRemoto) === '2', 'estoque remoto inicial');
        const inicioRemoto = Date.now();
        await chamar('post', '/api/compras', { fornecedorId: fornecedor.fornecedorId, itens: [{ produtoId: perecivel.id, quantidade: 1, lote: 'REMOTO', validade: '2099-12-31' }] });
        await esperar(async () => await saldo(listaRemota) === '3', 'SSE atualiza outro dispositivo');
        await esperar(async () => await saldoEstoque(estoqueRemoto) === '3', 'SSE atualiza estoque em outro dispositivo');
        const latenciaServidorMs = Date.now() - inicioRemoto;

        const fallback = await remoto.newPage();
        await fallback.addInitScript(() => {
            const intervalo = window.setInterval.bind(window);
            window.setInterval = (fn, ms, ...args) => intervalo(fn, ms === 15000 ? 300 : ms, ...args);
        });
        await fallback.route('**/api/pos/eventos', r => r.abort());
        await fallback.goto(baseURL + '/produtos-listagem.html');
        await fallback.locator('#pesquisaProdutos').fill('Sync leite');
        await esperar(async () => await saldo(fallback) === '3', 'saldo antes da consulta de segurança');
        const estoqueFallback = await remoto.newPage();
        await intervaloConsulta(estoqueFallback, 300);
        await estoqueFallback.route('**/api/pos/eventos', r => r.abort());
        await estoqueFallback.goto(baseURL + '/estoque.html');
        await estoqueFallback.locator('#pesquisaEstoque').fill('Sync leite');
        await estoqueFallback.locator('#buscaLote').fill('FALLBACK');
        await estoqueFallback.locator('#filtrosInventario button[type=submit]').click();
        await esperar(async () => await saldoEstoque(estoqueFallback) === '3', 'estoque antes da consulta de segurança');
        await chamar('post', '/api/compras', { fornecedorId: fornecedor.fornecedorId, itens: [{ produtoId: perecivel.id, quantidade: 1, lote: 'FALLBACK', validade: '2099-12-31' }] });
        await esperar(async () => await saldo(fallback) === '4', 'consulta periódica sem SSE nem BroadcastChannel');
        await esperar(async () => await saldoEstoque(estoqueFallback) === '4', 'estoque usa consulta periódica sem SSE nem BroadcastChannel');
        await esperar(async () => (await estoqueFallback.locator('#listaLotes').innerText()).includes('FALLBACK'), 'consulta periódica atualiza lotes');
        assert.equal(await estoqueFallback.locator('#buscaLote').inputValue(), 'FALLBACK');
        assert.match(await estoqueFallback.locator('#estadoInventario').innerText(), /Consulta automática/);
        await fallback.close();
        await estoqueFallback.close();

        const reconexao = await remoto.newPage();
        await intervaloConsulta(reconexao, 60000);
        let permitirStream = false;
        await reconexao.route('**/api/pos/eventos', r => permitirStream ? r.continue() : r.abort());
        await reconexao.goto(baseURL + '/estoque.html');
        await reconexao.locator('#pesquisaEstoque').fill('Sync arroz');
        await esperar(async () => await saldoEstoque(reconexao) === '4', 'estoque antes da reconexão');
        await chamar('post', '/api/compras', { fornecedorId: fornecedor.fornecedorId, itens: [{ produtoId: opcional.id, quantidade: 1 }] });
        permitirStream = true;
        await esperar(async () => await saldoEstoque(reconexao) === '5', 'reconexão recupera movimentação perdida', 8000);
        assert.equal(await reconexao.locator('#pesquisaEstoque').inputValue(), 'Sync arroz');
        await esperar(async () => (await reconexao.locator('#estadoInventario').innerText()).includes('conectada'), 'status de conexão restaurado');
        await reconexao.close();

        // Sem stream: trocar a exigência de validade e regularizar o lote avisam Produtos.
        const controle = await chamar('post', '/api/produtos', { nome: 'Sync controle de validade', codigo: 'SYNC-CONTROLE', precoCompra: 2, precoVenda: 3 });
        await chamar('post', '/api/compras', { fornecedorId: fornecedor.fornecedorId, itens: [{ produtoId: controle.id, quantidade: 5 }] });
        const produtosValidade = await c.newPage(), estoqueValidade = await c.newPage();
        for (const pagina of [produtosValidade, estoqueValidade]) {
            await intervaloConsulta(pagina, 60000);
            await pagina.route('**/api/pos/eventos', r => r.abort());
        }
        await produtosValidade.goto(baseURL + '/produtos-listagem.html');
        await produtosValidade.locator('#pesquisaProdutos').fill('Sync controle de validade');
        await estoqueValidade.goto(baseURL + '/estoque.html');
        await estoqueValidade.locator('#pesquisaEstoque').fill('Sync controle de validade');
        await estoqueValidade.locator('#buscaLote').fill('Sync controle de validade');
        await estoqueValidade.locator('#filtrosInventario button[type=submit]').click();
        await esperar(async () => await saldo(produtosValidade) === '5', 'saldo opcional vendável');
        await esperar(async () => await saldoEstoque(estoqueValidade) === '5', 'saldo físico opcional');
        estoqueValidade.once('dialog', dialog => dialog.accept());
        await estoqueValidade.locator(`[data-controle="${controle.id}"]`).click();
        await esperar(async () => await saldo(produtosValidade) === '0', 'exigir validade bloqueia Produtos sem F5');
        assert.equal(await estoqueValidade.locator('#listaEstoque tr').first().locator('td').nth(1).innerText(), '5');
        await esperar(async () => (await estoqueValidade.locator('#listaLotes').innerText()).includes('Validade pendente'), 'perecível sem data fica bloqueado');
        await estoqueValidade.locator('#listaLotes [data-regularizar]').click();
        await estoqueValidade.locator('#codigoLote').fill('REGULARIZADO');
        await estoqueValidade.locator('#dataLote').fill('2099-12-31');
        await estoqueValidade.locator('#formRegularizar').evaluate(form => form.requestSubmit());
        await esperar(async () => await saldo(produtosValidade) === '5', 'validade regularizada libera Produtos sem F5');
        await esperar(async () => await saldoEstoque(estoqueValidade) === '5', 'validade regularizada libera Estoque');
        assert.equal(await estoqueValidade.locator('#regularizarLote').evaluate(dialog => dialog.open), false);
        assert.match(await produtosValidade.locator('#estadoAtualizacaoProdutos').innerText(), /Atualização automática ativa/);
        await produtosValidade.close();
        await estoqueValidade.close();

        // Simula Live Server na porta configurada e encaminha somente a API ao banco descartável.
        const raiz = resolve('front-end-erp');
        live = http.createServer((req, res) => {
            const caminho = resolve(raiz, '.' + new URL(req.url, 'http://localhost').pathname);
            if (!caminho.startsWith(raiz + require('node:path').sep)) { res.writeHead(403).end(); return; }
            try {
                res.setHeader('Content-Type', ({ '.js': 'text/javascript', '.html': 'text/html', '.css': 'text/css' })[extname(caminho)] || 'application/octet-stream');
                res.end(readFileSync(caminho));
            } catch { res.writeHead(404).end(); }
        });
        await new Promise((resolve, reject) => { live.once('error', reject); live.listen(5501, '127.0.0.1', resolve); });
        const paginaLive = await remoto.newPage();
        const urlsLive = [];
        await paginaLive.route('http://127.0.0.1:8080/api/**', async r => {
            urlsLive.push(r.request().url());
            if (r.request().url().endsWith('/pos/eventos')) return r.abort();
            const response = await r.fetch({ url: r.request().url().replace(':8080/', ':18765/') });
            await r.fulfill({ response });
        });
        await paginaLive.goto('http://127.0.0.1:5501/produtos-listagem.html');
        await paginaLive.locator('#pesquisaProdutos').fill('Sync leite');
        await esperar(async () => await saldo(paginaLive) === '4', 'Live Server acessa API em outra porta');
        assert.ok(urlsLive.some(url => url.includes(':8080/api/produtos')));
        const cors = await api.get('/api/produtos', { headers: { ...headers, Origin: 'http://127.0.0.1:5501' } });
        assert.equal(cors.headers()['access-control-allow-origin'], 'http://127.0.0.1:5501');
        assert.deepEqual(erros, []);
        const resultado = { ok: true, latenciaBroadcastMs, latenciaServidorMs, casos: ['arquivos atuais servidos pelo Spring', 'compra/linhas/totais/Enter', 'lote opcional preservado', 'validade vencida rejeitada', 'erro de gravação preserva formulário', 'envio duplicado bloqueado', 'isolamento do canal por empresa', 'BroadcastChannel em Produtos e Estoque sem SSE', 'SSE em Produtos e Estoque sem BroadcastChannel', 'consulta periódica em Produtos e Estoque', 'filtros e lotes preservados', 'reconexão recupera movimentação perdida', 'perecível sem validade bloqueado e regularizado nas duas telas', 'Live Server/API/CORS'], errosJavaScript: erros };
        writeFileSync('target/sync-relatorio.json', JSON.stringify(resultado, null, 2));
        console.log(JSON.stringify(resultado));
    } finally {
        await browser.close();
        await api.dispose();
        if (live) await new Promise(r => live.close(r));
    }
})().catch(erro => { console.error(erro); process.exitCode = 1; });
