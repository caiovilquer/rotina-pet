# ADR-002 — Recorrência v2, cursor e tempo canônico

- Estado: aceito
- Data: 2026-07-14

## Contexto

A recorrência atual só representa intervalos de calendário, recalcula desde o
início e limita a geração total a 500 slots. Toda edição incrementa a revisão,
inclusive mudanças que não alteram horários. Isso impede intervalos horários,
pode deixar planos antigos sem novas ocorrências e invalida IDs desnecessariamente.

## Decisão

O domínio passará a representar quatro regras explícitas:

- `ONE_TIME`;
- `CALENDAR_INTERVAL`, para dias, semanas, meses e anos;
- `FIXED_INTERVAL`, para duração exata em horas sobre `Instant`;
- `DAILY_TIMES`, para uma lista única e ordenada de horários locais.

Cada plano preservará seu `zoneId`. Cada ocorrência armazenará o instante
canônico e a API devolverá também a representação local e a zona.
`FIXED_INTERVAL` soma duração sobre `Instant`; regras de calendário preservam o
relógio local. Em gaps de DST será usado `SHIFT_FORWARD_BY_GAP`; em overlaps,
`EARLIER_OFFSET`.

A materialização terá cursor por `(plan_id, schedule_revision)` com ordinal
global `Long`, próximo instante, último instante confirmado, estado e versão.
Plano e cursor serão bloqueados nessa ordem. Inserção do lote e avanço do cursor
estarão na mesma transação. O máximo de 500 será por lote, nunca vitalício.

A fórmula de IDs permanece byte a byte compatível:

```text
UUID.nameUUIDFromBytes("<planId>:<scheduleRevision>:<sequence decimal UTF-8>")
```

Mudanças em início, regra, horários ou zona criam nova revisão e tornam o cursor
anterior `SUPERSEDED`. Metadados, responsável, lembrete e escalonamento
preservam revisão, cursor e IDs, reconciliando snapshots e outboxes futuros.
Ocorrências concluídas ou canceladas no passado não são reescritas.

## Consequências

- A migration será expansiva e terá relatório de divergências no backfill.
- Materializador e edição compartilharão lock e fronteira transacional.
- Ações/outboxes de revisão antiga serão recusados, sem remapeamento silencioso.
- Regras ambíguas como “duas vezes ao dia” exigirão esclarecimento.

## Verificação

- IDs congelados da implementação atual permanecem iguais.
- Testes cobrem múltiplos lotes, retries, dois workers, corridas com edição,
  mais de 500 slots passados, gaps/overlaps de DST e rollback do backfill.
- “A cada 12 horas por 7 dias” gera exatamente os slots esperados.
