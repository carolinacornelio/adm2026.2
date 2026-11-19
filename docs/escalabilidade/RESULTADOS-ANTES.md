# Relatório de Medição Inicial (Antes) - Desafio 10: Escalabilidade e Desempenho

**Data da medição:** 18/11/2026, 22:00:18  
**Ambiente:** Spring Boot 3.5.5 + PostgreSQL (Arquitetura Monolítica / Instância Única)  
**Servidor:** http://localhost:8080

> **Objetivo:** Registrar o desempenho atual do sistema em instância única (baseline) para servir de referência na comparação com os testes após as melhorias.

---

## Teste 1: Tela de Login (Entrega Web / Servidor Tomcat)
- **Tela no ERP:** `login.html` (Acesso ao Sistema)  
- **Rota HTTP:** `GET /login.html`  
- **O que avalia:** Tempo de entrega da página HTML pelo servidor embutido.  
- **Ponto observado:** Verifica se o servidor web suporta múltiplos acessos simultâneos sem esgotar o pool de threads HTTP.

| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1. Carga Leve | 10 | 100 | 100 | 0 | 681.9 | 14.1 | 28.6 | 35.1 | 36.7 |
| 2. Carga Moderada | 30 | 300 | 300 | 0 | 944.4 | 31.2 | 74.6 | 174.7 | 254.0 |
| 3. Pico de Estresse | 60 | 600 | 600 | 0 | 2052.7 | 28.2 | 37.5 | 52.0 | 59.9 |

---

## Teste 2: Processamento de Login (Carga de CPU com BCrypt)
- **Tela no ERP:** Formulário de Login (`/api/auth/login`)  
- **Rota HTTP:** `POST /api/auth/login`  
- **O que avalia:** Comportamento da aplicação com vários usuários enviando credenciais ao mesmo tempo.  
- **Ponto observado:** Mede o peso na CPU causado pelo cálculo criptográfico do BCrypt na verificação de senhas.

| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1. Carga Leve | 10 | 100 | 0 | 100 | 71.3 | 136.3 | 168.5 | 207.9 | 208.4 |
| 2. Carga Moderada | 30 | 300 | 0 | 300 | 75.5 | 379.4 | 412.6 | 702.9 | 732.5 |
| 3. Pico de Estresse | 60 | 600 | 0 | 600 | 76.0 | 751.2 | 811.2 | 834.6 | 1506.5 |

---

## Teste 3: Tela de Dashboard (Carga de Banco / Consultas SQL Agregadas)
- **Tela no ERP:** Painel Principal do Dashboard (`/api/dashboard/indicadores`)  
- **Rota HTTP:** `GET /api/dashboard/indicadores`  
- **O que avalia:** Simula gestores abrindo o Dashboard para consultar totais de clientes, produtos e vendas.  
- **Ponto observado:** Executa 4 consultas SQL com `COUNT(*)` por requisição no PostgreSQL, pressionando o limite do pool HikariCP (configurado com 5 conexões).

| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1. Carga Leve | 10 | 100 | 100 | 0 | 15.1 | 636.0 | 988.8 | 1250.4 | 1489.7 |
| 2. Carga Moderada | 30 | 300 | 300 | 0 | 15.0 | 1907.3 | 5439.0 | 7648.3 | 9624.2 |
| 3. Pico de Estresse | 60 | 600 | 591 | 9 | 14.6 | 3902.2 | 9476.4 | 12005.5 | 12015.3 |

---

## Teste 4: Tela de Produtos e Estoque (Consultas com Relacionamentos)
- **Tela no ERP:** Listagem de Produtos e Saldo de Lotes (`/api/produtos`)  
- **Rota HTTP:** `GET /api/produtos`  
- **O que avalia:** Simula operadores e caixas consultando o catálogo de produtos e estoque no ERP e POS.  
- **Ponto observado:** Executa consultas com múltiplos `LEFT JOIN`s e cálculo de saldo de lotes em tempo real filtrados por empresa.

| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1. Carga Leve | 10 | 100 | 100 | 0 | 56.1 | 170.1 | 214.0 | 247.0 | 258.0 |
| 2. Carga Moderada | 30 | 300 | 300 | 0 | 74.3 | 383.9 | 426.8 | 439.4 | 741.0 |
| 3. Pico de Estresse | 60 | 600 | 600 | 0 | 74.2 | 769.9 | 835.7 | 1525.6 | 1567.0 |

---

## Principais Observações da Medição Inicial

1. **Comportamento sob concorrência:** Ao subir de 10 para 60 usuários simultâneos, a latência nos percentis p95 e p99 cresce de forma acentuada.
2. **Gargalo de conexão com o banco:** O pool único do HikariCP (limitado a 5 conexões) restringe o atendimento simultâneo em telas que disparam várias consultas SQL em sequência, como o Dashboard.
3. **Consumo de CPU na autenticação:** O cálculo de hash de senhas com BCrypt consome bastante CPU durante picos de login, mantendo a vazão próxima de 65–70 req/s mesmo com aumento de concorrência.
4. **Próximos passos para a arquitetura ("Depois"):**
   - Adicionar balanceamento de carga (Load Balancer) com múltiplas instâncias do ERP.
   - Migrar sessões locais para um modelo stateless (JWT) ou cache compartilhado (Redis).
   - Garantir isolamento de dados por cliente (estratégia de multitenancy).
