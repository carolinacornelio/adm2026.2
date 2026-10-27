// Executar exclusivamente contra PosBrowserServer, com PostgreSQL descartável.
const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const fs = require('node:fs');
const baseURL = 'http://127.0.0.1:18765';
const seguranca = { perguntaSeguranca1:'AMIGO_INFANCIA', perguntaSeguranca2:'EMPREGO_SONHOS', perguntaSeguranca3:'CIDADE_PAIS', respostaSeguranca1:'um', respostaSeguranca2:'dois', respostaSeguranca3:'tres' };

(async () => {
    const api = await request.newContext({ baseURL });
    const browser = await chromium.launch({ channel:'msedge', headless:true });
    const erros = [], senha = 'teste-compliance-123', sufixo = randomUUID();
    try {
        const registro = await api.post('/api/auth/cadastro', { data:{ email:`dono-${sufixo}@teste.invalid`, senha, nome:'Gestor teste', empresa:'Compliance teste', ...seguranca } });
        assert.equal(registro.status(),200,await registro.text());
        const dono = await registro.json();
        const headers = { Authorization:`Bearer ${dono.token}` };
        async function call(method,url,data,session=dono) {
            const r = await api[method](url,{data,headers:{Authorization:`Bearer ${session.token}`}});
            assert.ok(r.ok(),`${url}: ${r.status()} ${await r.text()}`);
            return r.status()===204 ? null : r.json();
        }
        async function login(email) {
            const primeira = await api.post('/api/auth/login',{data:{email,senha}});
            assert.equal(primeira.status(),200);
            const desafio = await primeira.json();
            assert.equal(desafio.configuracaoNecessaria,true);
            const segunda = await api.post('/api/auth/segundo-fator',{data:{desafio:desafio.desafio,...seguranca}});
            assert.equal(segunda.status(),200,await segunda.text());
            return segunda.json();
        }
        async function contexto(sessao) {
            const c = await browser.newContext({timezoneId:'America/Fortaleza',viewport:{width:1440,height:1050}});
            await c.addInitScript(s => {if(location.hostname==='127.0.0.1'){sessionStorage.setItem('authToken',s.token);sessionStorage.setItem('usuario',JSON.stringify(s.usuario));}},sessao);
            c.on('page',p=>p.on('pageerror',e=>erros.push(e.message)));
            return c;
        }
        async function painel(sessao) {
            const c=await contexto(sessao),p=await c.newPage();
            await p.goto(baseURL+'/compliance.html');
            await p.waitForFunction(()=>document.getElementById('perfilCompliance').textContent.includes('Perfil atual:'));
            await p.waitForFunction(()=>!document.getElementById('atualizarCompliance').disabled);
            p.on('dialog',d=>d.accept());
            return p;
        }
        async function operacao(p,locator,texto) {
            await locator.click();
            await p.getByText(texto,{exact:true}).waitFor();
        }
        const fornecedor=await call('post','/api/pessoas',{nome:'Fornecedor <b>seguro</b>',tipo:'FORNECEDOR'});
        const produto=await call('post','/api/produtos',{nome:'Produto perecível compliance',codigo:'COMP',precoCompra:6000,precoVenda:6500,controlaValidade:true});
        const cDono=await contexto(dono),p=await cDono.newPage();
        p.on('dialog',d=>d.accept());
        await p.goto(baseURL+'/dashboard.html');
        await p.locator('[data-pasta=ferramentas] summary').click();
        await p.getByRole('link',{name:'Compliance',exact:true}).click();
        await p.getByText('Perfil atual: Administrador',{exact:true}).waitFor();
        await p.locator('#tab-equipe').click();await p.locator('#novoUsuario summary').click();
        const emailGerente=`gerente-${sufixo}@teste.invalid`;
        await p.locator('#nomeColaborador').fill('Gerente');await p.locator('#emailColaborador').fill(emailGerente);await p.locator('#senhaColaborador').fill(senha);await p.locator('#perfilColaborador').selectOption('gerente_compras');
        await p.locator('#formUsuario [type=submit]').click();
        await p.getByText('Colaborador cadastrado nesta empresa. Ele definirá suas perguntas de segurança no primeiro login.',{exact:true}).waitFor();
        assert.match(await p.locator('#tabelaAuditoriaSoD').innerText(),/Gerente/);
        const gerente=await login(emailGerente);
        async function colaborador(nome,perfil) {
            const email=`${perfil}-${sufixo}@teste.invalid`;
            await call('post','/api/compliance/usuarios',{nome,email,senha,perfil});
            return login(email);
        }
        const diretor=await colaborador('Diretor','diretor'),financeiro=await colaborador('Financeiro','financeiro'),revisor=await colaborador('Revisor','compliance');
        console.log('PASS: acesso pela navegação atual, equipe da mesma empresa e primeiro login com configuração do segundo fator.');

        await p.locator('#tab-contratos').click();await p.locator('#novoContrato summary').click();
        await p.locator('#fornecedorContrato').selectOption(String(fornecedor.fornecedorId));await p.locator('#numeroContrato').fill('CTR-SERVICO');await p.locator('#valorContrato').fill('9000');await p.locator('#objetoContrato').fill('Manutenção <img src=x onerror=alert(1)>');await p.locator('#arquivoContrato').fill('https://example.com/contrato.pdf');
        await p.locator('#formContrato [type=submit]').click();await p.getByText('Contrato registrado.',{exact:true}).waitFor();
        assert.equal(await p.locator('#tabelaContratos img').count(),0);
        assert.match(await p.locator('#tabelaContratos').innerText(),/<img src=x onerror=alert\(1\)>/);
        assert.match(await p.locator('#tabelaContratos a').first().getAttribute('rel'),/noopener/);
        const contrato=(await call('get','/api/compliance/contratos')).find(c=>c.numeroContrato==='CTR-SERVICO');

        await p.goto(baseURL+'/compras.html');await p.waitForFunction(()=>!document.getElementById('btnFinalizarCompra').disabled);
        await p.locator('#selectFornecedor').selectOption(String(fornecedor.fornecedorId));
        const item=p.locator('#corpoItensCompra tr').first();await item.locator('[name=produto]').selectOption(String(produto.id));await item.locator('[name=quantidade]').fill('2');await item.locator('[name=lote]').fill('COMPLIANCE-LOTE');await item.locator('[name=validade]').fill('2099-12-31');
        await p.locator('#btnFinalizarCompra').click();await p.waitForURL('**/compliance.html');
        await p.locator('#tabelaComprasCompliance').getByText('Aguardando nível 1',{exact:true}).waitFor();
        const compra=(await call('get','/api/compras'))[0];
        assert.equal((await call('get','/api/produtos')).find(x=>x.id===produto.id).quantidadeEstoque,0);
        assert.equal(await p.locator('[data-acao=aprovar-compra]').count(),0);
        await p.locator('[data-acao=detalhe]').first().click();await p.locator('#detalhesCompliance[open]').waitFor();assert.match(await p.locator('#conteudoDetalhesCompliance').innerText(),/COMPLIANCE-LOTE/);await p.locator('[data-fechar=detalhesCompliance]').click();
        const pGerente=await painel(gerente);await operacao(pGerente,pGerente.locator('[data-acao=aprovar-compra]').first(),'Operação registrada.');assert.match(await pGerente.locator('#tabelaComprasCompliance').innerText(),/Aguardando nível 2/);
        assert.equal((await call('get','/api/produtos')).find(x=>x.id===produto.id).quantidadeEstoque,0);
        const pDiretor=await painel(diretor);await operacao(pDiretor,pDiretor.locator('[data-acao=aprovar-compra]').first(),'Operação registrada.');assert.match(await pDiretor.locator('#tabelaComprasCompliance').innerText(),/Concluída/);
        await call('post',`/api/compras/${compra.id}/aprovar`,{},diretor);
        assert.equal((await call('get','/api/produtos')).find(x=>x.id===produto.id).quantidadeEstoque,2);
        assert.equal((await call('get','/api/inventario/lotes')).filter(l=>l.produtoId===produto.id).length,1);
        const pFinanceiro=await painel(financeiro);await operacao(pFinanceiro,pFinanceiro.locator('[data-acao=pagar]').first(),'Pagamento registrado.');assert.match(await pFinanceiro.locator('#tabelaComprasCompliance').innerText(),/Paga/);
        console.log('PASS: compra de alto valor aguarda duas pessoas, preserva lote, efetiva uma única entrada e exige outro pagador.');

        await p.goto(baseURL+'/compras.html');await p.waitForFunction(()=>!document.getElementById('btnFinalizarCompra').disabled);
        await p.locator('#selectFornecedor').selectOption(String(fornecedor.fornecedorId));await p.locator('#tipoDocumentoCompra').selectOption('SERVICO');
        await p.locator('#contratoCompra').selectOption(String(contrato.id));await p.locator('#notaFiscalCompra').fill('NF-SERVICO-1');await p.locator('#descricaoServicoCompra').fill('Manutenção preventiva');await p.locator('#valorServicoCompra').fill('1000');
        await p.locator('#btnFinalizarCompra').click();await p.waitForURL('**/compliance.html');await p.locator('#tabelaComprasCompliance').getByText('Serviço NF-SERVICO-1',{exact:true}).waitFor();
        assert.equal((await call('get','/api/produtos')).find(x=>x.id===produto.id).quantidadeEstoque,2);
        await p.locator('[data-acao=detalhe]').first().click();await p.locator('#detalhesCompliance[open]').waitFor();assert.match(await p.locator('#conteudoDetalhesCompliance').innerText(),/Serviço sem movimentação de estoque/);await p.locator('[data-fechar=detalhesCompliance]').click();
        console.log('PASS: contrato com texto seguro e compra de serviço pela tela atual sem movimentar produtos.');

        await p.locator('#tab-banco').click();await p.locator('#novaAlteracaoBanco summary').click();await p.locator('#fornecedorBanco').selectOption(String(fornecedor.fornecedorId));
        await p.locator('#bancoFornecedor').fill('Banco teste');await p.locator('#agenciaFornecedor').fill('001');await p.locator('#contaFornecedor').fill('123');await p.locator('#comprovanteBanco').fill('https://example.com/banco.pdf');
        await p.locator('#formBanco [type=submit]').click();await p.getByText('Solicitação registrada. Aguarde a decisão de outra pessoa habilitada.',{exact:true}).waitFor();
        assert.equal(await p.locator('[data-acao=aprovar-banco]').count(),0);
        const bloqueada=await api.post('/api/compras',{headers,data:{fornecedorId:fornecedor.fornecedorId,itens:[{produtoId:produto.id,quantidade:1,lote:'NOVO',validade:'2099-12-31'}]}});
        assert.equal(bloqueada.status(),409);
        const pRevisor=await painel(revisor);await pRevisor.locator('#tab-banco').click();await operacao(pRevisor,pRevisor.locator('[data-acao=aprovar-banco]').first(),'Operação registrada.');
        const banco=(await call('get','/api/compliance/fornecedores')).find(f=>f.id===fornecedor.fornecedorId);assert.equal(banco.banco,'Banco teste');assert.equal(banco.dadosBancariosBloqueados,false);
        console.log('PASS: alteração bancária com comprovante, bloqueio preventivo e aprovação por outro usuário.');

        const alto=await call('post','/api/compliance/contratos',{fornecedorId:fornecedor.fornecedorId,numeroContrato:'CTR-ALTO',objeto:'Serviços anuais',valorTotal:60000,arquivoUrl:'https://example.com/anual.pdf'});
        await pRevisor.locator('#atualizarCompliance').click();await pRevisor.getByText('Painel atualizado.',{exact:true}).waitFor();await pRevisor.locator('#tab-contratos').click();
        await operacao(pRevisor,pRevisor.locator(`[data-acao=homologar][data-id="${alto.id}"]`),'Operação registrada.');
        await pRevisor.locator('#tab-riscos').click();await pRevisor.locator('[data-acao=revisar]').first().click();await pRevisor.locator('#justificativaAlerta').fill('Documentação conferida pela equipe.');await pRevisor.locator('#formRevisaoAlerta [type=submit]').click();await pRevisor.getByText('Revisão registrada.',{exact:true}).waitFor();
        assert.match(await pRevisor.locator('#tabelaTransacoesRisco').innerText(),/Documentação conferida pela equipe/);

        await p.locator('#atualizarCompliance').click();await p.getByText('Painel atualizado.',{exact:true}).waitFor();await p.locator('#tab-equipe').click();
        const gerenteId=gerente.usuario.id;await p.locator(`#perfil-${gerenteId}`).selectOption('financeiro');await operacao(p,p.locator(`[data-acao=perfil][data-id="${gerenteId}"]`),'Operação registrada.');
        await pGerente.locator('#atualizarCompliance').click();await pGerente.getByText('Perfil atual: Financeiro',{exact:true}).waitFor();
        console.log('PASS: homologação, revisão de risco e mudança de perfil vigente na sessão já aberta.');

        await p.locator('#tab-contratos').click();await p.setViewportSize({width:390,height:844});
        assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);
        fs.mkdirSync('target',{recursive:true});await p.screenshot({path:'target/compliance-mobile.png',fullPage:true});
        await p.setViewportSize({width:1440,height:1050});await p.screenshot({path:'target/compliance-desktop.png',fullPage:true});
        const anonimo=await browser.newContext(),paginaAnonima=await anonimo.newPage();await paginaAnonima.goto(baseURL+'/compliance.html');await paginaAnonima.waitForURL('**/login.html');await anonimo.close();
        assert.deepEqual(erros,[]);
        console.log('PASS: layout móvel, proteção de sessão e ausência de erros JavaScript.');
    } finally {await browser.close();await api.dispose();}
})().catch(erro=>{console.error(erro);process.exitCode=1;});
