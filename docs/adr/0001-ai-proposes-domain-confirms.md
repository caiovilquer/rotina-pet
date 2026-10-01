# ADR-001 — IA propõe; o domínio autorizado confirma

- Estado: aceito
- Data: 2026-07-14

## Contexto

O assistente interpretará texto e mídia para sugerir planos de cuidado. A saída
de um modelo é probabilística e não pode proteger autorização, idempotência,
concorrência ou invariantes clínicas e temporais.

## Decisão

A IA só produz rascunhos e explicações. Toda leitura ou mutação passa por casos
de uso determinísticos da aplicação, com o `HouseholdAccess` resolvido no
servidor. Criar um plano a partir de um rascunho exige, sem exceção:

1. confirmação humana explícita sobre um preview versionado;
2. autorização recalculada no momento da ação;
3. schema e invariantes do domínio válidos;
4. chave de idempotência e auditoria;
5. transação que associa unicamente o rascunho ao plano criado.

Nenhuma feature flag poderá ignorar confirmação, autorização ou validação. Não
haverá SQL gerado por modelo, acesso direto ao banco, ferramenta genérica de
escrita nem execução de ação a partir de texto livre.

## Consequências

- O formulário manual continua sendo um fallback independente do provider.
- Saída estruturada inválida termina em correção ou falha segura.
- Ports serão definidos por capacidade, próximos do consumidor, apenas quando
  houver caso de uso e adapter reais.
- O fluxo terá mais estados e persistência, em troca de rastreabilidade e
  proteção contra escrita silenciosa ou duplicada.

## Verificação

- Testes parametrizados provam que nenhuma combinação de flags confirma sem os
  controles acima.
- Replays e confirmações concorrentes retornam o mesmo plano.
- Testes de autorização cobrem OWNER, CAREGIVER, VIEWER e troca de família.
