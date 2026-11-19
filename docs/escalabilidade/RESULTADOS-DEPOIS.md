# Relatório de Medição Após Melhorias (Depois) - Desafio 10: Escalabilidade e Desempenho

**Data da medição:** 19/11/2026, 01:32:48  
**Ambiente:** Spring Boot 3.5.5 + PostgreSQL (3 Instâncias + Load Balancer + JWT Stateless + Pool HikariCP 20)  
**Servidor:** http://localhost:8080 (distribuído entre portas 8081, 8082 e 8083)

> **Objetivo:** Registrar o desempenho do ERP após as melhorias de escalabilidade (autenticação stateless via JWT, health check `/api/health`, pool de conexões ampliado, consulta única no Dashboard e balanceamento de carga entre 3 instâncias).

---

## Teste 1: Tela de Login (Entrega Web / Servidor Tomcat)
- **Tela no ERP:** `login.html` (Acesso ao Sistema)  
- **Rota HTTP:** `GET /login.html`  
- **O que avalia:** Tempo de entrega da página HTML pelo servidor embutido com balanceamento entre 3 instâncias.  
- **Ponto observado:** Distribuição de threads HTTP entre múltiplas instâncias.

| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1. Carga Leve | 10 | 100 | 100 | 0 | 531.4 | 18.4 | 40.4 | 52.7 | 53.5 |
| 2. Carga Moderada | 30 | 300 | 300 | 0 | 677.4 | 43.7 | 67.7 | 82.5 | 85.1 |
| 3. Pico de Estresse | 60 | 600 | 600 | 0 | 829.7 | 71.6 | 134.9 | 159.5 | 163.8 |

---

## Teste 2: Processamento de Login (Carga de CPU com BCrypt)
- **Tela no ERP:** Formulário de Login (`/api/auth/login`)  
- **Rota HTTP:** `POST /api/auth/login`  
- **O que avalia:** Comportamento com vários usuários enviando credenciais distribuídas entre 3 instâncias.  
- **Ponto observado:** Distribuição do cálculo criptográfico BCrypt entre múltiplos processos JVM.

| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1. Carga Leve | 10 | 100 | 0 | 100 | 110.5 | 86.4 | 188.3 | 195.5 | 196.4 |
| 2. Carga Moderada | 30 | 300 | 0 | 300 | 349.9 | 81.6 | 150.1 | 159.8 | 179.5 |
| 3. Pico de Estresse | 60 | 600 | 0 | 600 | 635.1 | 88.6 | 122.5 | 187.0 | 188.6 |

---

## Teste 3: Tela de Dashboard (Carga de Banco / Consulta SQL Única + Pool 20)
- **Tela no ERP:** Painel Principal do Dashboard (`/api/dashboard/indicadores`)  
- **Rota HTTP:** `GET /api/dashboard/indicadores`  
- **O que avalia:** Simula gestores abrindo o Dashboard após consolidação dos 5 COUNT(*) em 1 consulta SQL e pool HikariCP ampliado para 20 conexões por instância.  
- **Ponto observado:** Redução de contenção no banco de dados e eliminação de timeouts.

| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1. Carga Leve | 10 | 100 | 100 | 0 | 37.0 | 262.7 | 288.6 | 321.5 | 323.9 |
| 2. Carga Moderada | 30 | 300 | 300 | 0 | 131.7 | 221.7 | 288.6 | 298.9 | 299.4 |
| 3. Pico de Estresse | 60 | 600 | 600 | 0 | 265.4 | 219.7 | 239.4 | 263.9 | 275.0 |

---

## Teste 4: Tela de Produtos e Estoque (Consultas com Relacionamentos)
- **Tela no ERP:** Listagem de Produtos e Saldo de Lotes (`/api/produtos`)  
- **Rota HTTP:** `GET /api/produtos`  
- **O que avalia:** Simula operadores consultando catálogo de produtos e estoque distribuído entre 3 instâncias.  
- **Ponto observado:** Consultas com múltiplos LEFT JOINs distribuídas entre pools independentes.

| Cenário | Concorrência | Total Reqs | Sucessos | Falhas | Vazão (req/s) | Média (ms) | p95 (ms) | p99 (ms) | Máx (ms) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1. Carga Leve | 10 | 100 | 100 | 0 | 37.4 | 258.1 | 280.7 | 287.7 | 288.6 |
| 2. Carga Moderada | 30 | 300 | 300 | 0 | 135.8 | 214.2 | 274.3 | 282.5 | 283.0 |
| 3. Pico de Estresse | 60 | 600 | 600 | 0 | 276.6 | 206.7 | 230.0 | 243.3 | 290.1 |

---

