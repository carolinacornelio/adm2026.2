package com.erp;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Padroniza erros para que o apiService.js mostre mensagens amigáveis. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badRequest(IllegalArgumentException e) { return Map.of("message", e.getMessage()); }
    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> unauthorized(SecurityException e) { return Map.of("message", e.getMessage()); }
    @ExceptionHandler(PlanoInsuficienteException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, String> forbidden(PlanoInsuficienteException e) { return Map.of("message", e.getMessage()); }
}
