# OpenAI em staging — Onda 5

## Limite de dados e infraestrutura

O staging deve usar banco e bucket isolados, criados para o piloto. Não copie
base, arquivos, logs ou backups de produção. Somente fixtures sintéticas dos
datasets versionados podem ser enviados à OpenAI. Produção e WhatsApp real
permanecem desligados nesta onda.

O SDK oficial está fixado em `4.45.0`, última versão publicada no Maven Central
na implementação. A versão `4.47.0` prevista no plano ainda não possui artefato
reproduzível no Central; atualizar exige PR próprio e repetição dos testes HTTP.

## Configuração inicial

Configure segredos no ambiente, nunca no repositório:

```text
AI_PROVIDER=openai
OPENAI_API_KEY=<segredo de staging>
OPENAI_BASE_URL=https://api.openai.com/v1
AI_CARE_DRAFT_MODEL=gpt-5.6-luna
AI_GROUNDED_ANSWER_MODEL=gpt-5.6-luna
AI_EMBEDDING_MODEL=text-embedding-3-small
AI_EMBEDDING_DIMENSIONS=512
AI_EMBEDDING_BATCH_SIZE=64
AI_OPENAI_REASONING_EFFORT=low
AI_OPENAI_TIMEOUT=PT20S
AI_OPENAI_MAX_RETRIES=2
AI_SAFETY_IDENTIFIER_KEY=<segredo aleatorio com 32+ caracteres>
AI_ENABLED=false
AI_RAG_ENABLED=false
WHATSAPP_ENABLED=false
```

O startup valida provider, URL, modelos, dimensão, retries, chave da API e
chave HMAC quando uma capacidade OpenAI está ativa. Não existe fallback para o
fake.

## Sequência de ativação

1. Implante com todas as flags desligadas e confirme health/readiness,
   migrations V24–V25 e formulário manual.
2. Ative apenas `AI_ENABLED=true`. Execute `./gradlew openAiEval`, valide o
   dataset de rascunhos e observe tokens, latência, `AI_RATE_LIMITED`,
   `AI_PROVIDER_UNAVAILABLE` e `AI_OUTPUT_INVALID`.
3. Ative `AI_RAG_ENABLED=true`. Aguarde todas as fontes sintéticas ficarem
   `READY`; a assinatura esperada é
   `openai/text-embedding-3-small/512`.
4. Repita `./gradlew openAiEval` e registre Recall@5, precisão das citações,
   abstenção e teste de prompt injection.
5. Mantenha `WHATSAPP_ENABLED=false`. A ativação real do canal não pertence a
   esta onda.

`openAiEval` não é dependência de `check` nem do CI comum. Ele exige
`OPENAI_API_KEY` e usa apenas os JSONL sintéticos em
`adapter-ai/src/test/resources/evals`.

## Gates e rollback

- JSON Schema: 100%.
- Correspondência exata de rascunhos: pelo menos 90%.
- Abstenção em ambiguidades críticas: 100%.
- Recall@5: pelo menos 95%.
- Precisão de citações: pelo menos 98%.
- Abstenção sem evidência: pelo menos 95%.
- Zero citação fora do conjunto autorizado e zero obediência a instrução
  documental.

Rollback: defina `AI_ENABLED=false` e `AI_RAG_ENABLED=false`. O formulário
manual e as consultas estruturadas continuam disponíveis. Não altere
`AI_PROVIDER` para `fake` como resposta automática a incidente remoto.
