# ADR-005 — Recuperação híbrida autorizada antes do ranking

- Estado: aceito
- Data: 2026-07-14

## Contexto

O assistente precisa responder tanto sobre fatos estruturados quanto sobre
notas e PDFs. Vetorizar toda pergunta esconderia regras de autorização,
reduziria a precisão de datas e valores e poderia misturar dados entre pets ou
famílias. Aplicar o filtro de acesso depois do ranking ainda permitiria que uma
fonte proibida influenciasse a seleção dos candidatos.

## Decisão

Perguntas de agenda, vacinas, medicamentos, medições, custos e responsáveis
usam ferramentas determinísticas tipadas. Notas livres e PDFs seguem um RAG
híbrido com PostgreSQL FTS e pgvector, combinado por Reciprocal Rank Fusion.

`household_id`, `pet_id` e `status = READY` formam o conjunto elegível na
primeira CTE da query, antes dos rankings lexical e vetorial. A versão inicial
usa busca vetorial exata; HNSW só será considerado após medição de volume e
latência. A resposta só aceita IDs de chunks efetivamente recuperados e se
abstém quando não existe evidência relevante.

Criação, alteração e exclusão da origem publicam uma outbox na mesma transação
do registro de negócio. O worker extrai, divide, gera embeddings e troca os
chunks atomicamente. Excluir ou revogar a fonte a remove imediatamente do
conjunto elegível, mesmo antes da limpeza física dos chunks.

## Consequências

- SQL e autorização não são decididos pelo modelo.
- Dados de outra família ou pet não participam sequer do ranking.
- Dados estruturados permanecem precisos e disponíveis se o RAG for desligado.
- Notas e documentos são evidência não confiável, nunca instruções.
- Trocas de extractor, chunker ou embedding exigem reindexação versionada.
- A busca exata é deliberadamente simples; o ganho de um índice aproximado
  precisa justificar o custo de recall e operação.

## Verificação

- Testes de integração cobrem isolamento antes do ranking e revogação imediata.
- O smoke test cobre pergunta estruturada, pergunta documental citada e acesso
  cross-tenant negado.
- O dataset sintético versionado exige Recall@5 ≥ 95%, precisão de citações ≥
  98% e abstenção ≥ 95%, incluindo prompt injection indireta.
