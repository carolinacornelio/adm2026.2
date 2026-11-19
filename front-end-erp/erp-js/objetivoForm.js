import { abrirFormulario, esc } from './adminUi.js?v=okrs-4';
import { criarObjetivo } from './okrsService.js';
import { listarFuncionarios } from './funcionariosService.js';
import { pode } from './permissoesService.js';

export async function funcionariosParaOkrs(info) {
    if (!pode(info, 'users.view')) return [];
    return listarFuncionarios({ status: 'ativos' });
}

export function opcoesResponsaveis(funcionarios, atual, nomeAtual) {
    const opcoes = [{ valor: '', rotulo: 'Nenhum' }, ...funcionarios.map((f) => ({ valor: f.id, rotulo: f.nome }))];
    if (atual != null && !funcionarios.some((f) => f.id === atual)) {
        opcoes.push({ valor: atual, rotulo: nomeAtual || 'Responsável atual' });
    }
    return opcoes;
}

export async function novoObjetivo({ squads = [], funcionarios = [], squad }) {
    let objetivo;
    await abrirFormulario({
        titulo: squad ? `Novo objetivo · ${squad.nome}` : 'Novo objetivo',
        textoSalvar: 'Criar objetivo',
        campos: [
            { nome: 'titulo', rotulo: 'Título', obrigatorio: true, maxCaracteres: 150 },
            { nome: 'descricao', rotulo: 'Descrição', tipo: 'textarea', maxCaracteres: 1000 },
            ...(!squad && squads.length ? [{ nome: 'squadId', rotulo: 'Squad responsável', tipo: 'select',
                opcoes: [{ valor: '', rotulo: 'Nenhuma' }, ...squads.map((s) => ({ valor: s.id, rotulo: s.nome }))] }] : []),
            ...(funcionarios.length ? [{ nome: 'responsavelId', rotulo: 'Responsável', tipo: 'select',
                opcoes: opcoesResponsaveis(funcionarios) }] : []),
            { nome: 'periodoInicio', rotulo: 'Início do período', tipo: 'date', obrigatorio: true },
            { nome: 'periodoFim', rotulo: 'Fim do período', tipo: 'date', obrigatorio: true },
        ],
        salvar: async (v) => {
            if (v.periodoFim < v.periodoInicio) throw new Error('O fim do período deve ser igual ou posterior ao início.');
            objetivo = await criarObjetivo({ ...v, squadId: squad?.id ?? (Number(v.squadId) || null),
                responsavelId: Number(v.responsavelId) || null });
        },
    });
    return objetivo;
}
