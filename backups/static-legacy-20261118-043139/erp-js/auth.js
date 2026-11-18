/**
 * auth.js
 * ---------------------------------------------------------------------------
 * Controller das telas de autenticação (cadastro.html e login.html).
 * Este arquivo só cuida de: ler o formulário, validar no front-end, chamar
 * o authService, mostrar loading/erro e redirecionar em caso de sucesso.
 * Toda a regra "de verdade" (onde os dados são salvos, como autenticar)
 * fica em authService.js — este arquivo não sabe (nem precisa saber) se por
 * trás existe localStorage ou uma API Spring Boot.
 *
 * Observação: os formulários desta tela nasceram com o atributo
 * `novalidate`, então a validação HTML5 nativa do navegador fica desligada
 * de propósito — por isso validamos os campos manualmente aqui.
 * ---------------------------------------------------------------------------
 */

import { cadastrar, login, verificarSegundoFator } from './authService.js';
import { toUsuarioCadastroDTO, toUsuarioLoginDTO } from './dto.js';
import { formParaObjeto, iniciarCarregando, pararCarregando, mostrarErro, limparMensagem } from './uiService.js';

const REGEX_EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PERGUNTAS_SEGURANCA = [
    ['AMIGO_INFANCIA', 'Qual era o nome do seu melhor amigo de infância?'],
    ['EMPREGO_SONHOS', 'Qual é o emprego dos seus sonhos?'],
    ['CIDADE_PAIS', 'Em que cidade seus pais se conheceram?'],
    ['PRIMEIRA_ESCOLA', 'Qual era o nome da sua primeira escola?'],
    ['ANIMAL_ESTIMACAO', 'Qual era o nome do seu primeiro animal de estimação?'],
    ['RUA_INFANCIA', 'Em qual rua você morou na infância?'],
];

function validarCadastro(dados, formElement) {
    if (!dados.nome?.trim()) return 'Informe seu nome completo.';
    if (!dados.empresa?.trim()) return 'Informe o nome da sua empresa.';
    if (!REGEX_EMAIL.test(dados.email || '')) return 'Informe um e-mail válido.';
    if (!dados.senha || dados.senha.length < 6) return 'A senha deve ter pelo menos 6 caracteres.';
    if (dados.senha !== dados.confirmarSenha) return 'As senhas não coincidem.';
    const perguntas = [dados.perguntaSeguranca1, dados.perguntaSeguranca2, dados.perguntaSeguranca3];
    const respostas = [dados.respostaSeguranca1, dados.respostaSeguranca2, dados.respostaSeguranca3];
    if (perguntas.some(pergunta => !pergunta)) return 'Selecione as três perguntas de segurança.';
    if (new Set(perguntas).size !== perguntas.length) return 'Escolha três perguntas de segurança diferentes.';
    if (respostas.some(resposta => !resposta?.trim() || resposta.trim().length < 2)) {
        return 'Preencha as três respostas de segurança com pelo menos 2 caracteres.';
    }
    if (!formElement.querySelector('[name="terms"]').checked) {
        return 'É necessário aceitar os termos de uso para continuar.';
    }
    return null;
}

function validarLogin(dados) {
    if (!REGEX_EMAIL.test(dados.email || '')) return 'Informe um e-mail válido.';
    if (!dados.senha) return 'Informe sua senha.';
    return null;
}

function configurarFormularioCadastro(formCadastro) {
    formCadastro.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        limparMensagem(formCadastro);

        const dados = formParaObjeto(formCadastro);
        const erroValidacao = validarCadastro(dados, formCadastro);
        if (erroValidacao) {
            mostrarErro(formCadastro, erroValidacao);
            return;
        }

        const botaoEnviar = formCadastro.querySelector('button[type="submit"]');
        iniciarCarregando(botaoEnviar, 'Criando conta...');

        try {
            const usuarioCadastroDTO = toUsuarioCadastroDTO(dados);
            await cadastrar(usuarioCadastroDTO);
            window.location.href = 'selecionar-sistema.html';
        } catch (erro) {
            mostrarErro(formCadastro, erro.message || 'Não foi possível criar sua conta.');
        } finally {
            pararCarregando(botaoEnviar);
        }
    });
}

function configurarFormularioLogin(formLogin) {
    formLogin.addEventListener('submit', async (evento) => {
        evento.preventDefault();
        limparMensagem(formLogin);

        const dados = formParaObjeto(formLogin);
        const erroValidacao = validarLogin(dados);
        if (erroValidacao) {
            mostrarErro(formLogin, erroValidacao);
            return;
        }

        const botaoEnviar = formLogin.querySelector('button[type="submit"]');
        iniciarCarregando(botaoEnviar, 'Entrando...');

        try {
            const usuarioLoginDTO = toUsuarioLoginDTO(dados);
            const desafio = await login(usuarioLoginDTO);
            abrirSegundoFator(desafio);
        } catch (erro) {
            mostrarErro(formLogin, erro.message || 'Não foi possível entrar.');
        } finally {
            pararCarregando(botaoEnviar);
        }
    });
}

function abrirSegundoFator(dados) {
    const modal = document.getElementById('segundoFatorModal');
    const form = document.getElementById('formSegundoFator');
    if (!modal || !form) throw new Error('Tela de segundo fator não encontrada.');
    form.reset();
    form.dataset.configuracao = String(Boolean(dados.configuracaoNecessaria));
    form.querySelector('[name="desafio"]').value = dados.desafio;
    document.getElementById('configurarPerguntasLogin').hidden = !dados.configuracaoNecessaria;
    document.getElementById('segundoFatorDescricao').textContent = dados.configuracaoNecessaria
        ? 'Antes de entrar, configure três perguntas para proteger esta conta.'
        : 'Responda às três perguntas para confirmar sua identidade.';

    const selects = [...form.querySelectorAll('[name^="perguntaSeguranca"]')];
    selects.forEach((select, indice) => {
        select.replaceChildren();
        if (dados.configuracaoNecessaria) {
            select.disabled = false;
            select.append(new Option('Selecione uma pergunta', '', true, true));
            PERGUNTAS_SEGURANCA.forEach(([id, texto]) => select.append(new Option(texto, id)));
        } else {
            const pergunta = dados.perguntas[indice];
            select.append(new Option(pergunta.texto, pergunta.id, true, true));
            select.disabled = true;
        }
    });
    modal.hidden = false;
    document.body.classList.add('modal-open');
    form.querySelector('[name="respostaSeguranca1"]').focus();
}

function fecharSegundoFator() {
    const modal = document.getElementById('segundoFatorModal');
    if (modal) modal.hidden = true;
    document.body.classList.remove('modal-open');
}

function configurarSegundoFator() {
    const form = document.getElementById('formSegundoFator');
    if (!form) return;
    const selects = [...form.querySelectorAll('[name^="perguntaSeguranca"]')];
    const atualizarOpcoes = () => {
        const usadas = new Set(selects.map(select => select.value).filter(Boolean));
        selects.forEach(select => [...select.options].forEach(option => {
            option.disabled = Boolean(option.value && option.value !== select.value && usadas.has(option.value));
        }));
    };
    selects.forEach(select => select.addEventListener('change', atualizarOpcoes));
    document.getElementById('fecharSegundoFator')?.addEventListener('click', fecharSegundoFator);
    form.addEventListener('submit', async evento => {
        evento.preventDefault();
        limparMensagem(form);
        const dados = formParaObjeto(form);
        const respostas = [dados.respostaSeguranca1, dados.respostaSeguranca2, dados.respostaSeguranca3];
        if (respostas.some(resposta => !resposta?.trim() || resposta.trim().length < 2)) {
            mostrarErro(form, 'Preencha as três respostas com pelo menos 2 caracteres.');
            return;
        }
        if (form.dataset.configuracao === 'true') {
            const perguntas = [dados.perguntaSeguranca1, dados.perguntaSeguranca2, dados.perguntaSeguranca3];
            if (perguntas.some(pergunta => !pergunta) || new Set(perguntas).size !== 3) {
                mostrarErro(form, 'Escolha três perguntas de segurança diferentes.');
                return;
            }
        }
        const botao = form.querySelector('button[type="submit"]');
        iniciarCarregando(botao, 'Verificando...');
        try {
            await verificarSegundoFator(dados);
            window.location.href = 'selecionar-sistema.html';
        } catch (erro) {
            mostrarErro(form, erro.message || 'Não foi possível confirmar sua identidade.');
        } finally {
            pararCarregando(botao);
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const seletorPlano = document.querySelector('#formCadastro [name="plano"]');
    if (seletorPlano) {
        const planoSelecionado = new URLSearchParams(window.location.search).get('plano');
        if (['BASE', 'PLUS', 'PREMIUM'].includes(planoSelecionado)) {
            seletorPlano.value = planoSelecionado;
        }
    }

    const formCadastro = document.getElementById('formCadastro');
    if (formCadastro) {
        configurarFormularioCadastro(formCadastro);
        const seletoresPergunta = [...formCadastro.querySelectorAll('[name^="perguntaSeguranca"]')];
        const atualizarPerguntasDisponiveis = () => {
            const selecionadas = new Set(seletoresPergunta.map(select => select.value).filter(Boolean));
            seletoresPergunta.forEach(select => {
                [...select.options].forEach(option => {
                    option.disabled = Boolean(option.value && option.value !== select.value && selecionadas.has(option.value));
                });
            });
        };
        seletoresPergunta.forEach(select => select.addEventListener('change', atualizarPerguntasDisponiveis));
        atualizarPerguntasDisponiveis();
    }

    const formLogin = document.getElementById('formLogin');
    if (formLogin) configurarFormularioLogin(formLogin);
    configurarSegundoFator();
});
