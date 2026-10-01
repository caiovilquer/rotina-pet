# Rascunhos de cuidado com assistente

## Fluxo e garantias

`POST /api/v1/assistant/care-drafts` transforma uma descrição de texto em um
rascunho versionado. Somente um `OWNER` pode gerar, corrigir, confirmar ou
cancelar. O backend resolve família, papel, timezone, pets e responsáveis em
cada ação; IDs produzidos pelo extractor nunca são tratados como autorização.

O rascunho passa por `PROCESSING`, `NEEDS_INPUT` ou `READY`. Campos ausentes e
ambiguidades bloqueantes exigem revisão. A confirmação adquire lock do draft,
revalida sua versão e expiração, cria o plano com `source_draft_id` único e
marca o draft `CONFIRMED` na mesma transação. Replays devolvem o mesmo plano.

O texto original não é persistido. O banco mantém seu hash SHA-256, o payload
mínimo revisável, evidências curtas, proveniência e auditoria append-only. A
tabela `ai_interaction` guarda somente metadados operacionais.

## Configuração

| Variável | Padrão | Uso |
|---|---:|---|
| `AI_PROVIDER` | `fake` | Seleciona exatamente `fake` ou `openai`; não existe fallback automático |
| `AI_ENABLED` | `false` (`true` no perfil `dev`) | Desliga a geração sem afetar o formulário manual |
| `AI_CARE_DRAFT_MODEL` | `gpt-5.6-luna` | Modelo da Responses API para extração |
| `AI_OPENAI_REASONING_EFFORT` | `low` | Esforço de raciocínio do modelo remoto |
| `AI_OPENAI_TIMEOUT` | `PT20S` | Timeout total de cada chamada |
| `AI_OPENAI_MAX_RETRIES` | `2` | Retries do SDK apenas para falhas transitórias |
| `OPENAI_API_KEY` | — | Obrigatória quando OpenAI e alguma capacidade de IA estão ativas |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | Base da API; permite servidor simulado nos testes |
| `AI_SAFETY_IDENTIFIER_KEY` | — | Chave HMAC com pelo menos 32 caracteres |
| `AI_DRAFT_TTL` | `PT24H` | Janela para revisão e confirmação |
| `AI_MAX_INPUT_CHARACTERS` | `4000` | Limite determinístico antes do extractor |

O provider local não usa rede ou credenciais. O provider OpenAI usa Responses
API, `store=false`, Structured Outputs e referências opacas para pets; IDs
internos não são enviados como `safety_identifier`. As métricas
`rotinapet.ai.requests` e `rotinapet.ai.latency` ficam disponíveis pelo
Actuator conforme a política de acesso já existente.

## Avaliação e falhas

O dataset `adapter-ai/src/test/resources/evals/care-draft-eval-v1.jsonl` cobre
tipos, datas, recorrência, campos ausentes e ambiguidades em português. A suíte
falha se a correspondência exata ficar abaixo do quality gate. “Duas vezes ao
dia” sem horários exatos deve sempre se abster.

Falha do provider produz um draft `FAILED` com mensagem segura. A UI oferece o
formulário manual, sem tentar confirmar ou reutilizar saída parcial. Rollback
operacional é `AI_ENABLED=false`; drafts e planos já confirmados continuam
consultáveis e auditáveis.

O limite do piloto é de 10 gerações por tutor e família por hora. A aplicação
aplica o mesmo contador antes do provider tanto para REST quanto para WhatsApp.
