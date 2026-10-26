/**
 * compliance.js
 * ---------------------------------------------------------------------------
 * CONTROLADOR DO PAINEL DE COMPLIANCE E GESTÃO DE RISCOS
 * ---------------------------------------------------------------------------
 */
import { complianceService } from './complianceService.js';
import { pessoasService } from './pessoasService.js';
import { notificarSucesso, notificarErro } from './uiService.js';

document.addEventListener('DOMContentLoaded', async () => {
    configurarAbas();
    configurarFormularioContrato();
    await carregarTudo();
});

function configurarAbas() {
    const botoes = document.querySelectorAll('.aba-btn');
    const conteudos = document.querySelectorAll('.aba-conteudo');

    botoes.forEach(btn => {
        btn.addEventListener('click', () => {
            botoes.forEach(b => b.classList.remove('ativa'));
            conteudos.forEach(c => c.classList.remove('ativa'));

            btn.classList.add('ativa');
            const alvoId = btn.getAttribute('data-alvo');
            const conteudoAlvo = document.getElementById(alvoId);
            if (conteudoAlvo) {
                conteudoAlvo.classList.add('ativa');
            }
        });
    });
}

async function carregarTudo() {
    await Promise.all([
        carregarTransacoesRisco(),
        carregarAuditoriaSoD(),
        carregar4Olhos(),
        carregarContratos(),
        carregarSelectFornecedores()
    ]);
}

async function carregarTransacoesRisco() {
    try {
        const alertas = await complianceService.listarAlertas();
        const totalEl = document.getElementById('totalTransacoesRisco');
        if (totalEl) totalEl.textContent = alertas.length;

        const tbody = document.getElementById('tabelaTransacoesRisco');
        if (!tbody) return;

        if (alertas.length === 0) {
            tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--cinza);">Nenhuma transação de risco detectada no momento. Ambiente em plena conformidade.</td></tr>`;
            return;
        }

        tbody.innerHTML = alertas.map(a => {
            const dataFmt = a.dataHora ? new Date(a.dataHora).toLocaleString('pt-BR') : '-';
            const valorFmt = (a.valorTransacao || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
            const severidadeClass = `badge-${(a.nivelSeveridade || 'alta').toLowerCase()}`;

            return `
                <tr>
                    <td><strong>${dataFmt}</strong></td>
                    <td>${escapeHtml(a.usuarioNome || 'Sistema')} <br/><small style="color: var(--cinza);">${escapeHtml(a.usuarioEmail || '')}</small></td>
                    <td><code>${escapeHtml(a.documentoReferencia || '-')}</code></td>
                    <td><strong>${valorFmt}</strong></td>
                    <td><span class="badge ${severidadeClass}">${escapeHtml(a.motivoAlerta)}</span></td>
                    <td><span class="badge ${severidadeClass}">${escapeHtml(a.nivelSeveridade)}</span></td>
                    <td><small>${escapeHtml(a.detalhes || '-')}</small></td>
                </tr>
            `;
        }).join('');
    } catch (e) {
        console.error('Erro ao listar alertas de compliance:', e);
    }
}

async function carregarAuditoriaSoD() {
    try {
        const auditoria = await complianceService.auditarSoD();
        const tbody = document.getElementById('tabelaAuditoriaSoD');
        if (!tbody) return;

        const violacoes = auditoria.filter(u => u.tipoRisco !== 'CONFORME');
        const totalEl = document.getElementById('totalViolacoesSoD');
        if (totalEl) totalEl.textContent = violacoes.length;

        tbody.innerHTML = auditoria.map(u => {
            const severidadeClass = u.tipoRisco === 'CONFORME' ? 'badge-conforme' : (u.severidade === 'CRITICA' ? 'badge-critica' : 'badge-alta');
            return `
                <tr>
                    <td><strong>${escapeHtml(u.nome)}</strong></td>
                    <td>${escapeHtml(u.email)}</td>
                    <td><code>${escapeHtml(u.perfil)}</code></td>
                    <td><span class="badge ${severidadeClass}">${escapeHtml(u.tipoRisco)}</span></td>
                    <td><span class="badge ${severidadeClass}">${escapeHtml(u.severidade)}</span></td>
                    <td>${escapeHtml(u.diagnostico)}</td>
                    <td><small style="color: var(--azul); font-weight: 600;">${escapeHtml(u.acaoRecomendada)}</small></td>
                </tr>
            `;
        }).join('');
    } catch (e) {
        console.error('Erro ao auditar SoD:', e);
    }
}

async function carregar4Olhos() {
    try {
        const pendentes = await complianceService.listarAlteracoesBancariasPendentes();
        const totalEl = document.getElementById('totalPendencias4Olhos');
        if (totalEl) totalEl.textContent = pendentes.length;

        const tbody = document.getElementById('tabela4Olhos');
        if (!tbody) return;

        if (pendentes.length === 0) {
            tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: var(--cinza);">Nenhuma alteração bancária aguardando segundo aprovador.</td></tr>`;
            return;
        }

        tbody.innerHTML = pendentes.map(p => {
            const dataFmt = p.dataSolicitacao ? new Date(p.dataSolicitacao).toLocaleString('pt-BR') : '-';
            return `
                <tr>
                    <td>${dataFmt}</td>
                    <td><strong>${escapeHtml(p.fornecedorNome || '')}</strong></td>
                    <td>${escapeHtml(p.solicitanteNome || '')}</td>
                    <td>
                        Banco: <strong>${escapeHtml(p.banco || '-')}</strong> | Agência: <strong>${escapeHtml(p.agencia || '-')}</strong><br/>
                        Conta: <strong>${escapeHtml(p.conta || '-')}</strong> | PIX: <strong>${escapeHtml(p.chavePix || '-')}</strong>
                    </td>
                    <td>
                        <a href="${escapeHtml(p.arquivoComprobatorioUrl || '#')}" target="_blank" style="color: var(--azul); text-decoration: underline;">
                            📄 Ver Documento
                        </a>
                    </td>
                    <td>
                        <button class="acao-tabela-btn acao-tabela-aprovar" data-aprovar-4olhos="${p.id}" type="button">Aprovar (N2)</button>
                        <button class="acao-tabela-btn acao-tabela-rejeitar" data-rejeitar-4olhos="${p.id}" type="button">Rejeitar</button>
                    </td>
                </tr>
            `;
        }).join('');

        tbody.querySelectorAll('[data-aprovar-4olhos]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-aprovar-4olhos');
                if (confirm('Deseja homologar a alteração de dados bancários deste fornecedor?')) {
                    try {
                        await complianceService.aprovarAlteracaoBancaria(id);
                        notificarSucesso('Dados bancários homologados com sucesso pelo Princípio de Quatro Olhos.');
                        await carregar4Olhos();
                    } catch (err) {
                        notificarErro(err.message || 'Erro ao aprovar alteração.');
                    }
                }
            });
        });

        tbody.querySelectorAll('[data-rejeitar-4olhos]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-rejeitar-4olhos');
                if (confirm('Deseja rejeitar e cancelar esta solicitação de alteração bancária?')) {
                    try {
                        await complianceService.rejeitarAlteracaoBancaria(id);
                        notificarSucesso('Alteração bancária rejeitada.');
                        await carregar4Olhos();
                    } catch (err) {
                        notificarErro(err.message || 'Erro ao rejeitar alteração.');
                    }
                }
            });
        });
    } catch (e) {
        console.error('Erro ao carregar 4 olhos:', e);
    }
}

async function carregarContratos() {
    try {
        const contratos = await complianceService.listarContratos();
        const totalEl = document.getElementById('totalContratos');
        if (totalEl) totalEl.textContent = contratos.filter(c => c.status === 'APROVADO').length;

        const tbody = document.getElementById('tabelaContratos');
        if (!tbody) return;

        if (contratos.length === 0) {
            tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--cinza);">Nenhum contrato formal de fornecedor cadastrado.</td></tr>`;
            return;
        }

        tbody.innerHTML = contratos.map(c => {
            const valorFmt = (c.valorTotal || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
            const statusClass = c.status === 'APROVADO' ? 'badge-conforme' : 'badge-alta';
            const precisaHomologar = c.status !== 'APROVADO';

            return `
                <tr>
                    <td><strong>${escapeHtml(c.numeroContrato)}</strong></td>
                    <td>${escapeHtml(c.fornecedorNome || '')}</td>
                    <td>${escapeHtml(c.objeto || '-')}</td>
                    <td><strong>${valorFmt}</strong></td>
                    <td><span class="badge ${statusClass}">${escapeHtml(c.status)}</span></td>
                    <td>
                        <a href="${escapeHtml(c.arquivoUrl || '#')}" target="_blank" style="color: var(--azul); text-decoration: underline;">
                            📄 Documento
                        </a>
                    </td>
                    <td>
                        ${precisaHomologar ? `
                            <button class="acao-tabela-btn acao-tabela-aprovar" data-homologar-contrato="${c.id}" type="button">Homologar</button>
                        ` : '<span style="color: var(--verde); font-weight: 600;">✓ Homologado</span>'}
                    </td>
                </tr>
            `;
        }).join('');

        tbody.querySelectorAll('[data-homologar-contrato]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const id = btn.getAttribute('data-homologar-contrato');
                try {
                    await complianceService.homologarContrato(id);
                    notificarSucesso('Contrato homologado formalmente.');
                    await carregarContratos();
                } catch (err) {
                    notificarErro(err.message || 'Erro ao homologar contrato.');
                }
            });
        });
    } catch (e) {
        console.error('Erro ao carregar contratos:', e);
    }
}

async function carregarSelectFornecedores() {
    try {
        const pessoas = await pessoasService.listarPessoas();
        const fornecedores = pessoas.filter(p => p.tipo !== 'CLIENTE');
        const select = document.getElementById('selectFornecedorContrato');
        if (!select) return;

        select.innerHTML = '<option value="">Selecione um fornecedor</option>' + 
            fornecedores.map(f => `<option value="${f.id}">${escapeHtml(f.nome)} (${escapeHtml(f.documento || 'Sem doc')})</option>`).join('');
    } catch (e) {
        console.error('Erro ao listar fornecedores para contratos:', e);
    }
}

function configurarFormularioContrato() {
    const btnAbrir = document.getElementById('btnAbrirModalContrato');
    const container = document.getElementById('formNovoContratoContainer');
    const btnCancelar = document.getElementById('btnCancelarContrato');
    const form = document.getElementById('formContrato');

    if (btnAbrir && container) {
        btnAbrir.addEventListener('click', () => {
            container.style.display = container.style.display === 'none' ? 'block' : 'none';
        });
    }

    if (btnCancelar && container) {
        btnCancelar.addEventListener('click', () => {
            container.style.display = 'none';
            form?.reset();
        });
    }

    if (form) {
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const fornecedorId = document.getElementById('selectFornecedorContrato').value;
            const numeroContrato = document.getElementById('inputNumeroContrato').value.trim();
            const valorTotal = parseFloat(document.getElementById('inputValorContrato').value);
            const arquivoUrl = document.getElementById('inputArquivoContrato').value.trim();
            const objeto = document.getElementById('inputObjetoContrato').value.trim();

            if (!fornecedorId || !numeroContrato || isNaN(valorTotal) || !arquivoUrl) {
                notificarErro('Preencha todos os campos obrigatórios e anexe o contrato.');
                return;
            }

            try {
                await complianceService.criarContrato({
                    fornecedorId: parseInt(fornecedorId),
                    numeroContrato,
                    valorTotal,
                    arquivoUrl,
                    objeto
                });
                notificarSucesso('Contrato cadastrado com sucesso.');
                form.reset();
                container.style.display = 'none';
                await carregarContratos();
                await carregarTransacoesRisco();
            } catch (err) {
                notificarErro(err.message || 'Erro ao cadastrar contrato.');
            }
        });
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
