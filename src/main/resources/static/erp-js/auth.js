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

import { cadastrar, login } from './authService.js';
import { toUsuarioCadastroDTO, toUsuarioLoginDTO } from './dto.js';
import { formParaObjeto, iniciarCarregando, pararCarregando, mostrarErro, limparMensagem } from './uiService.js';

const REGEX_EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function validarCadastro(dados, formElement) {
    if (!dados.nome?.trim()) return 'Informe seu nome completo.';
    if (!dados.empresa?.trim()) return 'Informe o nome da sua empresa.';
    if (!REGEX_EMAIL.test(dados.email || '')) return 'Informe um e-mail válido.';
    if (!dados.senha || dados.senha.length < 6) return 'A senha deve ter pelo menos 6 caracteres.';
    if (dados.senha !== dados.confirmarSenha) return 'As senhas não coincidem.';
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
            window.location.href = 'dashboard.html';
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
            await login(usuarioLoginDTO);
            window.location.href = 'dashboard.html';
        } catch (erro) {
            mostrarErro(formLogin, erro.message || 'Não foi possível entrar.');
        } finally {
            pararCarregando(botaoEnviar);
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const formCadastro = document.getElementById('formCadastro');
    if (formCadastro) configurarFormularioCadastro(formCadastro);

    const formLogin = document.getElementById('formLogin');
    if (formLogin) configurarFormularioLogin(formLogin);
});
