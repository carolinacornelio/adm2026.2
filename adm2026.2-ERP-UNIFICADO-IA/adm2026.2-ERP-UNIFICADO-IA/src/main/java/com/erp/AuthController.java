package com.erp;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service=service; }
    /** Cria empresa, usuário e sessão a partir do formulário de cadastro. */
    @PostMapping("/cadastro") Map<String,Object> cadastrar(@RequestBody Map<String,Object> d) { return service.cadastrar(d); }
    /** Valida as credenciais e devolve o token das próximas requisições. */
    @PostMapping("/login") Map<String,Object> login(@RequestBody Map<String,Object> d) { return service.login(d); }
    /** Confirma as respostas do segundo fator e somente então cria a sessão. */
    @PostMapping("/segundo-fator") Map<String,Object> segundoFator(@RequestBody Map<String,Object> d) { return service.verificarSegundoFator(d); }
}
