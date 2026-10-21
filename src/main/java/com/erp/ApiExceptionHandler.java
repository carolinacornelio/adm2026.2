package com.erp;

import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

/** Padroniza erros para que o apiService.js mostre mensagens amigáveis. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalidPayload(Exception e) { return Map.of("message", "Dados inválidos. Confira produtos, quantidades, valores e a confirmação do pagamento."); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badRequest(IllegalArgumentException e) { return Map.of("message", e.getMessage()); }
    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> unauthorized(SecurityException e) { return Map.of("message", e.getMessage()); }
    @ExceptionHandler(PlanoInsuficienteException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, String> forbidden(PlanoInsuficienteException e) { return Map.of("message", e.getMessage()); }
    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> missingAuthorization(MissingRequestHeaderException e) { return Map.of("message", "Faça login para continuar."); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(DataIntegrityViolationException e) { return Map.of("message", "Já existe um registro com esses dados ou ele está sendo usado em outra operação."); }
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> databaseUnavailable(DataAccessException e) { return Map.of("message", "Não foi possível acessar o banco de dados. Verifique a configuração da conexão e se o script do banco foi aplicado."); }
}
