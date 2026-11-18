package com.erp;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    private final Autorizacao autorizacao;
    public AuthController(AuthService service, Autorizacao autorizacao) { this.service=service; this.autorizacao=autorizacao; }
    /** Identidade e permissões efetivas. Serve para o frontend ajustar a interface; a segurança real está no backend. */
    @GetMapping("/me") Map<String,Object> me(@RequestHeader("Authorization") String authorization) {
        Autorizacao.Contexto c = autorizacao.contexto(authorization);
        return Map.of("usuario", service.usuario(authorization.substring(7)), "proprietario", c.proprietario(), "permissoes", c.permissoes());
    }
    @PostMapping("/alterar-senha") void alterarSenha(@RequestHeader("Authorization") String authorization, @RequestBody Map<String,Object> d) {
        Autorizacao.Contexto c = autorizacao.contexto(authorization);
        service.alterarSenha(c.usuarioId(), String.valueOf(d.get("senhaAtual")), String.valueOf(d.get("novaSenha")), authorization.substring(7));
    }
    /** Cria empresa, usuário e sessão a partir do formulário de cadastro. */
    @PostMapping("/cadastro") Map<String,Object> cadastrar(@RequestBody Map<String,Object> d) { return service.cadastrar(d); }
    /** Valida as credenciais e devolve o token das próximas requisições. */
    @PostMapping("/login") Map<String,Object> login(@RequestBody Map<String,Object> d) { return service.login(d); }
    /** Confirma as respostas do segundo fator e somente então cria a sessão. */
    @PostMapping("/segundo-fator") Map<String,Object> segundoFator(@RequestBody Map<String,Object> d) { return service.verificarSegundoFator(d); }
    @PostMapping("/logout") void sair(@RequestHeader("Authorization") String authorization) { service.sair(authorization); }
}
