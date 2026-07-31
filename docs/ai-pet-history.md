# Assistente de histórico do pet

## Escopo

Cada pergunta é independente e retorna um `answerId`. Não há memória de chat.
O catálogo determinístico responde sobre última vacina, medicamentos, agenda
de hoje, atrasos, peso, temperatura, custos e responsáveis. Custos continuam
restritos a quem possui `MANAGE_FINANCES`. Pedidos de diagnóstico, prescrição
ou dose são recusados antes de qualquer recuperação documental.

Perguntas sobre conteúdo livre usam RAG somente sobre notas de registros de
saúde e PDFs clínicos autorizados. Imagens permanecem fora do índice até a fase
de OCR/visão. Respostas documentais trazem citações ou informam evidência
insuficiente; o sistema não completa lacunas por inferência.

## APIs autenticadas

| Método e rota | Uso |
|---|---|
| `POST /api/v1/assistant/questions` | Pergunta independente para um `petId` |
| `POST /api/v1/assistant/answers/{id}/feedback` | Feedback da resposta |
| `GET /api/v1/assistant/knowledge-sources?petId={id}` | Estado das fontes do pet |
| `POST /api/v1/assistant/knowledge-sources/{id}/reindex` | Repetir uma indexação com falha |

Abrir um PDF citado passa novamente pelo endpoint autenticado de download. O
frontend ignora caminhos fornecidos pela resposta e reconstrói a rota a partir
do `resourceId` validado.

## Indexação e recuperação

Registro, atividade e outbox de uma nota são gravados na mesma transação. PDFs
entram na outbox depois que upload, checksum, assinatura e vínculo clínico são
validados. O backfill inclui notas e PDFs já existentes.

O worker usa lock concorrente, tentativas com backoff e dead letter. Ele
preserva página e offsets, cria chunks de aproximadamente 350–700 tokens com
overlap, gera embeddings em lote e publica chunks + estado `READY` na mesma
transação. Alterações usam checksum e versões; exclusões deixam a fonte
inelegível imediatamente e a limpeza é idempotente.

Chunks usam `VECTOR(512)`. Fonte e chunk recebem a assinatura completa do
embedding, por exemplo `openai/text-embedding-3-small/512`. Divergência de
modelo, dimensão, extractor ou chunker coloca a fonte em `PENDING` e agenda
reindexação antes do próximo lote.

A query limita o universo por família, pet e fonte `READY` antes do ranking.
FTS em português e distância cosseno são combinados por RRF. Nesta versão a
busca vetorial é exata. O gerador recebe apenas evidências recuperadas e suas
citações são validadas contra os IDs permitidos.

## Configuração e rollback

| Variável | Padrão | Uso |
|---|---:|---|
| `AI_RAG_ENABLED` | `false` (`true` no perfil `dev`) | Liga RAG e worker; consultas estruturadas continuam ativas quando desligado |
| `AI_MAX_QUESTION_CHARACTERS` | `1000` | Limite da pergunta |
| `AI_RETRIEVAL_LIMIT` | `5` | Máximo de chunks enviados ao gerador |
| `AI_RAG_MIN_SEMANTIC_SCORE` | `0.08` | Limiar semântico mínimo; FTS positivo continua elegível |
| `AI_INDEX_BATCH_SIZE` | `10` | Fontes adquiridas por execução |
| `AI_INDEX_MAX_ATTEMPTS` | `5` | Limite antes de dead letter |
| `AI_GROUNDED_ANSWER_MODEL` | `gpt-5.6-luna` | Modelo da resposta fundamentada |
| `AI_EMBEDDING_MODEL` | `text-embedding-3-small` | Modelo de embeddings |
| `AI_EMBEDDING_DIMENSIONS` | `512` | Dimensão explícita, igual à coluna pgvector |
| `AI_EMBEDDING_BATCH_SIZE` | `64` | Textos por chamada de embedding |

O rollback operacional é `AI_RAG_ENABLED=false`. Isso para geração documental
e indexação sem remover fontes, respostas auditadas ou consultas
determinísticas. Reativar a flag retoma jobs pendentes.

## Privacidade, segurança e avaliação

Pergunta e resposta completas não são gravadas em auditoria nem telemetria. O
banco mantém SHA-256 da pergunta, tipo, versões, contagem de citações, resultado
e metadados do provider. Chunks existem enquanto a fonte autorizada existir.

Documentos são dados não confiáveis: não escolhem ferramentas, não alteram
instruções e trechos com marcadores de injeção são neutralizados no baseline
local. A saída não pode citar chunks fora da recuperação autorizada.

O dataset `adapter-ai/src/test/resources/evals/pet-history-rag-eval-v1.jsonl`
é totalmente sintético e versionado. A suíte bloqueia regressões de Recall@5,
precisão de citação, abstenção e prompt injection. Os contadores
`rotinapet.rag.answers` e o timer `rotinapet.rag.answer.latency` não carregam
conteúdo do usuário.

O limite do piloto é de 20 perguntas por tutor e família por hora. Evidências
saem do processo com referências `chunk-N`; o adapter converte as referências
citadas de volta para UUIDs e o caso de uso ainda valida o subconjunto
autorizado antes de responder.
