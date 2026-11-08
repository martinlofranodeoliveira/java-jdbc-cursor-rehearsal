# Laboratório de Cursores e Cancelamento JDBC PostgreSQL

[![CI](https://github.com/martinlofranodeoliveira/java-jdbc-cursor-rehearsal/actions/workflows/ci.yml/badge.svg)](https://github.com/martinlofranodeoliveira/java-jdbc-cursor-rehearsal/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)
[![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue.svg)](https://www.postgresql.org/)

Laboratório empírico de validação de protocolo e suite diagnóstica projetada para testar, verificar e entender o gerenciamento de cursores no servidor PostgreSQL e o cancelamento de statements JDBC sob condições reais de rede.

## Recursos Principais

1. **Ciclo de Vida do Portal no Servidor**: Verifica as condições exatas necessárias para criar e reter portais de servidor em `pg_cursors`.
2. **Verificação de Localidade de Sessão**: Demonstra que entradas em `pg_cursors` pertencem exclusivamente à sessão proprietária e são invisíveis para conexões observadoras externas.
3. **Contabilidade de Demanda e Barreiras de Lock**: Distingue a avaliação de linhas do consumo da aplicação utilizando barreiras de advisory lock em PL/pgSQL e contabilidade estrita de sequências.
4. **Transições de Estado do Driver**: Evidencia por que `Statement.cancel()` é inoperante durante buscas posteriores de lotes de cursor (devido à transição do estado do driver para `IDLE`) e comprova que `PGConnection.cancelQuery()` cancela buscas bloqueadas com SQLState `57014`.
5. **Fronteiras Transacionais**: Comprova a destruição de portais não-holdable em operações de `commit` e `rollback`.
6. **Injeção de Falhas sem Root**: Utiliza um relay TCP local não privilegiado para testar timeouts de leitura de socket e envenenamento de conexão por TCP RST.

## Cenários Cobertos

| # | Cenário | Foco de Verificação |
|---|---------|---------------------|
| 1 | Criação de Portal e Localidade de Lotes | Verifica lotes de 4 linhas, entrada em `pg_cursors` na mesma sessão e ausência na sessão observadora. |
| 2 | Contabilidade de Demanda e Barreira de Lock | Observa a pausa na linha 5 via `pg_locks` antes da aplicação solicitar o lote 2. |
| 3 | Controles Não-Cursor | Comprova a avaliação antecipada de todas as linhas quando `autocommit=true` ou `fetchSize=0`. |
| 4 | Cancelamento do Lote Inicial e Query Timeout | Comprova que `Statement.cancel()` e `setQueryTimeout(1)` cancelam consultas iniciais com SQLState `57014`. |
| 5 | Cancelamento de Lote Posterior via PGConnection | Comprova estado `IDLE` do driver em buscas posteriores; cancela via `PGConnection.cancelQuery()`. |
| 6 | Fronteiras de Commit e Rollback | Demonstra o fechamento de cursores não-holdable após encerramento transacional e recuperação íntegra. |
| 7 | Timeout de Socket Nativo via Relay | Injeta retenção de resposta no servidor, disparando `socketTimeout` e descarte seguro da conexão. |
| 8 | RST de Transporte e Envenenamento de Conexão | Simula TCP RST (`SO_LINGER=0`), capturando falha de transporte e forçando descarte da conexão. |

## Requisitos
- Java 21+ (OpenJDK / Eclipse Temurin)
- Apache Maven 3.9+
- Utilitários de cliente e servidor PostgreSQL 16+ ou 17+ (`initdb`, `postgres`, `pg_isready`)

## Compilação e Execução

### Gerar Executável Standalone
```bash
mvn clean package -DskipTests
```

### Executar Rehearsal com Asserções CLI
```bash
java -jar target/java-jdbc-cursor-rehearsal-0.1.0-standalone.jar --assert
```

### Executar Rehearsal com Relatório JSON
```bash
java -jar target/java-jdbc-cursor-rehearsal-0.1.0-standalone.jar --json
```

### Executar Suite de Testes Offline
```bash
/bin/sh scripts/test-offline.sh
```

### Executar Demonstração Offline
```bash
/bin/sh scripts/demo-offline.sh --assert
```

## Licença
Licença MIT. Copyright (c) 2026 Lofrano Tecnologia.
