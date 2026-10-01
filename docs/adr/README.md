# Registros de decisão arquitetural

Os ADRs registram decisões duráveis do RotinaPet. Uma decisão aceita só muda
por um novo ADR que a substitua; alterações de implementação permanecem nos
commits e na documentação da capacidade correspondente.

| ADR | Estado | Decisão |
|---|---|---|
| [ADR-001](0001-ai-proposes-domain-confirms.md) | Aceito | IA propõe; o domínio autorizado confirma |
| [ADR-002](0002-schedule-rule-v2.md) | Aceito | Recorrência v2, cursor e tempo canônico |
| [ADR-003](0003-postgresql-pgvector-only.md) | Aceito | PostgreSQL 16 + pgvector como único banco suportado |
| [ADR-004](0004-ai-provider-boundary.md) | Aceito | Provider de IA isolado por ports de capacidade e artefatos versionados |
| [ADR-005](0005-authorized-hybrid-retrieval.md) | Aceito | Recuperação híbrida com autorização aplicada antes do ranking |
