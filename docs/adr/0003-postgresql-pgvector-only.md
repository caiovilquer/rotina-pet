# ADR-003 — PostgreSQL 16 + pgvector como único banco suportado

- Estado: aceito
- Data: 2026-07-14

## Contexto

O perfil local e o smoke test usavam um banco em memória, enquanto produção e
parte das integrações usavam PostgreSQL. Concorrência, locks, `ON CONFLICT`,
full-text search e vetores tornariam a compatibilidade dupla cara e pouco fiel.

## Decisão

PostgreSQL 16 com pgvector é o único banco suportado em desenvolvimento,
integração, CI e produção. O ambiente local usa Docker Compose; smoke, seeder e
integrações usam Testcontainers. Testes unitários continuam sem banco.

A imagem de referência fica pinada em
`pgvector/pgvector:0.8.2-pg16-bookworm`. A migration PostgreSQL V20 executa
`CREATE EXTENSION IF NOT EXISTS vector`; falhar nela significa que o ambiente
não atende ao contrato. Localizações Flyway são explícitas (`common` e
`postgresql`). Código e migrations específicos do banco anterior são removidos.

Não será criado um banco vetorial externo na versão 1. `SemanticSearchPort`,
quando necessário, manterá o núcleo independente do adapter sem duplicar dados
ou autorização em outro serviço.

## Consequências

- Docker é necessário para suítes de integração e para o ambiente local.
- Compilação e testes unitários permanecem disponíveis sem Docker.
- O deploy precisa permitir a extensão `vector` antes da aplicação subir.
- O ganho é paridade de dialeto e testes reais das primitivas de concorrência e
  busca que a feature utilizará.

## Verificação

- CI executa `./gradlew check` com Testcontainers.
- Um teste consulta `pg_extension` e exige a versão pinada.
- Um guard de CI rejeita dependência, URL, migration ou branch específico do
  banco removido.
