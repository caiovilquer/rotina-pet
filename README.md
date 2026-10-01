# RotinaPet

Aplicação full‑stack para controlar cuidados recorrentes com animais de estimação.
Tutore(s) podem cadastrar seus pets, planejar eventos (vacinas, medicamentos,
banhos, serviços etc.) e receber lembretes por e‑mail no dia correto.

## Funcionalidades principais

- **Autenticação e cadastro de tutores**
  - Signup público (`/api/v1/public/signup`) e login com JWT (`/api/v1/auth/login`).
  - Perfil protegido, atualização e exclusão de tutores.

- **Gestão de pets**
  - CRUD completo de animais ligados a um tutor.
  - Paginação de listas e filtro por tutor.
  - Fotos de pets e avatar do tutor em object storage privado, com upload
    direto, validação de conteúdo e limpeza automática de órfãos.

- **Planos de cuidado e ocorrências**
  - Um plano guarda regra, recorrência, orientações e política de lembrete;
    cada execução concreta é uma ocorrência independente e auditável.
  - Tipos de evento: `VACCINE`, `MEDICINE`, `DIARY`, `BREED`, `SERVICE`.
  - Ocorrências dos próximos 90 dias são materializadas de forma idempotente,
    sem depender da conclusão da anterior. Edições preservam o histórico e
    substituem somente execuções futuras ainda pendentes.
  - Conclusão com lock pessimista, chave de idempotência, trilha de autoria e
    proteção contra registro em dobro. O próprio autor pode desfazer por até
    10 minutos. Escritas no endpoint legado `/events` retornam `410 Gone` para
    impedir duas fontes de verdade durante a transição.

- **Linha do tempo clínica**
  - Histórico cronológico de vacinas, medicamentos, consultas, exames, sintomas
    e cuidados diários, com produto, dose, lote, profissional, clínica e custo.
  - Séries de peso, temperatura e condição corporal com unidades e faixas
    consistentes.
  - Documentos e imagens privados no mesmo object storage, com autorização por
    tutor, download forçado e URLs assinadas curtas.
  - Edição concorrente protegida por versão e lock, sem misturar fatos clínicos
    com a agenda futura.

- **Cuidado compartilhado com responsabilidade**
  - Famílias isoladas por contexto, com papéis de proprietário, cuidador e
    visitante aplicados no backend — não apenas escondidos na interface.
  - Convites pessoais de uso único, responsáveis por cuidado, autoria de cada
    confirmação, atividade recente e passagem de turno entre cuidadores.
  - Cuidados críticos podem escalar por e-mail quando continuam pendentes após
    o prazo escolhido, usando outbox idempotente e retry seguro.

- **Resumo veterinário e planejamento financeiro**
  - Resumo por período com adesão, histórico clínico, medições e documentos,
    pronto para impressão e consulta.
  - Links somente leitura com escopo mínimo, expiração, revogação, segredo no
    fragmento da URL e downloads privados de curta duração.
  - Custos realizados separados de previsões dos planos, com acesso financeiro
    exclusivo do proprietário e destaques estritamente descritivos.

- **Assistente com consultas estruturadas e fontes**
  - Agenda, vacinas, medicamentos, medições, custos e responsáveis consultados
    por ferramentas determinísticas, sem SQL gerado por modelo.
  - Notas e PDFs pesquisados com FTS + pgvector, autorização antes do ranking,
    citações verificadas e resposta segura quando a evidência é insuficiente.
  - Indexação assíncrona e repetível, com estado visível no documento e
    exclusão imediata do conjunto pesquisável quando a origem é removida.

- **Criação segura por WhatsApp**
  - Vínculo temporário de uso único, identidade e payloads sensíveis cifrados,
    webhook assinado e processamento assíncrono por inbox/outbox.
  - Mensagens de texto geram rascunhos com botões para confirmar, corrigir ou
    cancelar; reentregas do webhook e cliques repetidos não duplicam o plano.

- **Lembretes automáticos e confiáveis**
  - Um scheduler a cada 5 minutos estende o horizonte e detecta ocorrências no
    momento configurado, apenas
    enfileira o lembrete (outbox); um segundo scheduler, a cada 5 minutos,
    entrega de fato, com retry automático em caso de falha da API de e‑mail.
    Um lembrete nunca é enviado duas vezes (idempotência por evento) nem se
    perde silenciosamente se o processo reiniciar no meio do dia.
  - Servidor SMTP fake (MailHog) para desenvolvimento.
  - Todos os schedulers (lembretes, entrega, limpeza de segurança) usam
    ShedLock para rodar em uma única instância quando a aplicação estiver
    replicada.

- **Segurança e documentação**
  - Spring Security + JWT, senhas com BCrypt.
  - Redefinição de senha com token de uso único; a troca de senha e a
    emissão/invalidação de tokens são atômicas (`TransactionPort`).
  - OpenAPI/Swagger UI em `/swagger-ui.html`.
  - PostgreSQL 16 + pgvector no ambiente local e nas suítes de integração.

- **Observabilidade**
  - Spring Boot Actuator: `/actuator/health` (público), `/actuator/info` e
    `/actuator/metrics` (autenticados, como o resto da API).
  - Cada requisição recebe um `X-Request-Id` (gerado se o cliente não
    enviar um), propagado na resposta e no log, para correlacionar todas as
    linhas de uma mesma requisição sem precisar de tracing distribuído.

## Arquitetura

Estrutura hexagonal (ports & adapters) em oito módulos. `core` e `application`
são Kotlin puro — nenhuma dependência de Spring, Jakarta ou qualquer
framework — e essa fronteira é verificada em tempo de teste (testes de
arquitetura com [Konsist](https://docs.konsist.lemonappdev.com/), rodando via
`./gradlew check`), não apenas por convenção.

| Módulo                  | Papel                                                                    |
|-------------------------|---------------------------------------------------------------------------|
| **core**                | Entidades (Tutor, Pet, Event) e Value Objects (Email, Phone, etc.). Zero dependências. |
| **application**         | Ports de entrada/saída, comandos, resultados e os use cases (services). Kotlin puro — sem Spring. |
| **adapter-ai**          | Extração estruturada, embeddings, PDF e resposta fundamentada atrás de ports por capacidade. O provider local de desenvolvimento é determinístico e não escreve no domínio. |
| **adapter-rest**        | Controllers REST, DTOs, `ApiExceptionHandler`, Spring Security (JWT, CORS), emissão de token, hash de senha. |
| **adapter-persistence** | Entidades JPA, repositórios Spring Data, mappers, adapters de persistência e migrações Flyway. Testes de integração sobem um Postgres real via Testcontainers (requer Docker). |
| **adapter-messaging**   | Adapters de e-mail e do canal WhatsApp (segurança, gateway fake e Cloud API da Meta). |
| **adapter-storage**     | Adaptador S3 compatível com Railway Buckets e Cloudflare R2; URLs pré-assinadas curtas para leitura e escrita. |
| **bootstrap**           | Ponto de entrada Spring Boot: wiring manual dos use cases (`UseCaseWiring`), schedulers, configuração de ambiente (`application*.yml`, SSL, JVM). |

`core` e `application` não conhecem nenhum dos três adapters nem o
`bootstrap`; os adapters não se importam entre si; todo o grafo de beans dos
use cases é montado explicitamente em `UseCaseWiring` (bootstrap), já que os
services não carregam `@Service`/`@Value`/`@ConfigurationProperties`.

A configuração segura do bucket e do CORS está em
[`docs/object-storage.md`](docs/object-storage.md).
O desenho, a migração e a estratégia operacional do Ciclo 2 estão em
[`docs/cycle-2-care-plans.md`](docs/cycle-2-care-plans.md).
As garantias, APIs e implantação do Ciclo 3 estão em
[`docs/cycle-3-clinical-timeline.md`](docs/cycle-3-clinical-timeline.md).
O modelo de autorização, os convites e a operação do Ciclo 4 estão em
[`docs/cycle-4-shared-care.md`](docs/cycle-4-shared-care.md).
As garantias de privacidade, o modelo financeiro e a implantação do Ciclo 5
estão em [`docs/cycle-5-veterinary-finance.md`](docs/cycle-5-veterinary-finance.md).
As decisões duráveis da evolução de IA estão em [`docs/adr`](docs/adr/README.md).
O fluxo de rascunhos, suas flags, privacidade e avaliação estão em
[`docs/ai-care-drafts.md`](docs/ai-care-drafts.md).
As consultas estruturadas, o RAG, a indexação, as flags e os quality gates estão
em [`docs/ai-pet-history.md`](docs/ai-pet-history.md).
O vínculo, a configuração da Meta e a operação do canal de texto estão em
[`docs/whatsapp-text-integration.md`](docs/whatsapp-text-integration.md).

## Requisitos

- JDK 21+
- Kotlin 2.0 / Spring Boot 3.5 (versões centralizadas em `gradle/libs.versions.toml`)
- Docker (para PostgreSQL/pgvector, MailHog, MinIO e para os testes de integração,
  que sobem o mesmo banco via Testcontainers — `./gradlew check` falha sem
  Docker disponível)
- Gradle Wrapper (`./gradlew`)

## Executando o projeto

```bash
# PostgreSQL/pgvector + MailHog (e-mail) + MinIO (bucket S3 local)
docker compose up -d

# API com PostgreSQL, Swagger e object storage apontando para o MinIO
scripts/run-dev.sh
```

Com apenas o PostgreSQL local ativo, o storage pode permanecer desligado e a API
pode ser iniciada com `MAIL_FROM=noreply@localhost ./gradlew :bootstrap:bootRun`.

| Serviço   | URL |
|-----------|-----|
| API       | `https://localhost:8443` |
| Swagger   | `https://localhost:8443/swagger-ui.html` |
| PostgreSQL | `localhost:5432` (`rotinapet` / `rotinapet`) |
| MailHog   | `http://localhost:8025` |
| MinIO API | `http://localhost:9000` |
| MinIO UI  | `http://localhost:9001` (user/pass: `minioadmin` / `minioadmin`) |

Detalhes do bucket local: [`docs/object-storage.md`](docs/object-storage.md#minio-local).

## Producao (sem Docker)

```bash
./gradlew :bootstrap:bootJar
scripts/run-prod.sh
```
# Timezones e contrato de datas

Cada plano preserva um `zoneId` IANA e armazena `startAt`/`dueAt` como `Instant`
canônico. Inputs sem offset são interpretados no fuso do plano; valores ISO
8601 com offset ou `Z` mantêm o instante informado. A API devolve o instante,
a representação local e o timezone. `FIXED_INTERVAL` mede duração transcorrida;
regras de calendário e horários diários preservam o relógio local conforme o
[ADR-002](docs/adr/0002-schedule-rule-v2.md).
