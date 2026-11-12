import { apiService } from './apiService.js';
import { estaAutenticado } from './authService.js';
import { escapeHtml as e } from './uiService.js';

const $ = selector => document.querySelector(selector);
let produtos = [];
const moeda = valor => valor == null ? '—' : Number(valor).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
const percentual = valor => valor == null ? '—' : `${Number(valor) > 0 ? '+' : ''}${Number(valor).toLocaleString('pt-BR', { maximumFractionDigits: 2 })}%`;
const calcular = (nosso, concorrente) => {
    const valor = Number(concorrente);
    if (!Number.isFinite(valor) || valor <= 0) return null;
    return ((Number(nosso) - valor) / valor) * 100;
};

function linha(p) {
    const entrada = p.precoConcorrente == null ? '' : Number(p.precoConcorrente).toFixed(2);
    return `<tr data-produto="${e(p.id)}"><td>${e(p.nome)}</td><td>${e(moeda(p.nossoPreco))}</td><td><input class="preco-concorrente" data-id="${e(p.id)}" type="number" min="0" max="99999999.99" step="0.01" value="${e(entrada)}" aria-label="Preço do concorrente para ${e(p.nome)}"/></td><td class="diferenca">${e(percentual(p.diferencaPercentual))}</td><td>${e(p.vendidos30Dias)}</td></tr>`;
}
function desenhar() {
    const termo = $('#pesquisaConcorrencia').value.trim().toLowerCase();
    const filtrados = produtos.filter(p => p.nome.toLowerCase().includes(termo));
    $('#listaConcorrencia').innerHTML = filtrados.map(linha).join('') || '<tr><td colspan="5" class="estado-vazio">Nenhum produto encontrado.</td></tr>';
}
async function carregar() {
    try {
        produtos = await apiService.get('/concorrencia');
        desenhar();
        $('#estadoConcorrencia').textContent = `${produtos.length} produto(s) atualizado(s)`;
    } catch (erro) {
        $('#estadoConcorrencia').textContent = erro.message;
        $('#listaConcorrencia').innerHTML = '<tr><td colspan="5" class="estado-vazio">Não foi possível consultar o relatório.</td></tr>';
    }
}
async function salvar(input) {
    const produto = produtos.find(p => String(p.id) === input.dataset.id);
    if (!produto) return;
    const texto = input.value.trim();
    const valor = texto === '' ? null : Number(texto);
    if (texto !== '' && (!Number.isFinite(valor) || valor < 0)) { input.setCustomValidity('Informe um preço válido.'); input.reportValidity(); return; }
    input.setCustomValidity('');
    input.disabled = true;
    try {
        const atualizado = await apiService.put(`/concorrencia/${input.dataset.id}`, { precoConcorrente: texto === '' ? null : valor.toFixed(2) });
        Object.assign(produto, atualizado);
        const diferenca = input.closest('tr').querySelector('.diferenca');
        diferenca.textContent = percentual(calcular(produto.nossoPreco, texto));
        $('#estadoConcorrencia').textContent = 'Preço do concorrente salvo.';
    } catch (erro) { $('#estadoConcorrencia').textContent = erro.message; }
    finally { input.disabled = false; }
}
document.addEventListener('DOMContentLoaded', () => {
    if (!estaAutenticado()) return;
    $('#pesquisaConcorrencia').addEventListener('input', desenhar);
    $('#listaConcorrencia').addEventListener('input', ev => {
        const input = ev.target.closest('.preco-concorrente'); if (!input) return;
        const produto = produtos.find(p => String(p.id) === input.dataset.id);
        input.closest('tr').querySelector('.diferenca').textContent = percentual(calcular(produto.nossoPreco, input.value));
    });
    $('#listaConcorrencia').addEventListener('change', ev => { const input = ev.target.closest('.preco-concorrente'); if (input) void salvar(input); });
    void carregar();
});
