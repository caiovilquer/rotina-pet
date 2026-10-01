# Ciclo 5 — resumo veterinário e planejamento financeiro

O Ciclo 5 reúne contexto para a consulta veterinária e visibilidade de custos sem transformar o RotinaPet em prontuário, diagnóstico ou produto financeiro. Fatos clínicos, despesas realizadas e previsões continuam sendo conceitos independentes.

## Entregas

- Resumo veterinário por pet e período, com adesão aos cuidados, medicamentos, sintomas, demais fatos clínicos, medições e documentos.
- Impressão pelo navegador e exportação das despesas em CSV compatível com planilhas.
- Links externos somente leitura, revogáveis, com validade, período e escopo explícitos.
- Despesas realizadas por pet e categoria, somadas aos custos já registrados na caderneta.
- Previsão de até 90 dias baseada no custo estimado dos planos de cuidado.
- Destaques descritivos de concentração por categoria ou pelos próximos sete dias.

## Limites de domínio

| Informação | Fonte | Pode alterar o passado? |
|---|---|---|
| Fato clínico | `health_record` | Apenas pela edição versionada do próprio registro |
| Despesa direta | `expense` | Sim, por CRUD versionado e restrito ao proprietário |
| Previsão | snapshot em `care_occurrence` | Não; editar o plano substitui somente ocorrências futuras pendentes |

O total realizado apresenta separadamente despesas diretas e custos clínicos. A interface avisa que consultas e exames já informados na caderneta não devem ser lançados novamente como despesa direta. Nenhum destaque oferece recomendação clínica ou financeira.

## Compartilhamento seguro

1. Somente o papel `OWNER`, por meio de `SHARE_VETERINARY_SUMMARY`, cria, lista ou revoga links.
2. O cliente gera uma URL com o segredo no fragmento (`/shared/veterinary#TOKEN`). Fragmentos não são enviados ao servidor web nem entram no `Referer`.
3. O frontend remove o fragmento do endereço assim que o lê e envia o token no corpo de um `POST`.
4. O token contém 256 bits aleatórios. O banco armazena somente seu SHA-256; o valor original é retornado uma única vez.
5. Notas, custos e documentos começam desabilitados. O link fica preso ao pet e ao período escolhidos.
6. As validades permitidas são 1 h, 24 h, 72 h, 7 dias e 30 dias, com no máximo 20 links ativos por família.
7. A resolução pública usa rate limit por IP, lock pessimista e uma mensagem genérica para token inválido, expirado ou revogado.
8. Documentos compartilhados são revalidados contra família, pet, período, registro, finalidade e estado do arquivo antes de receber uma URL assinada de cinco minutos.
9. Respostas públicas usam `no-store`, `no-referrer`, `nosniff` e CSP restritiva. A página pública não depende de login.

O acesso ao resumo incrementa contador e data do último acesso. Revogação é imediata e protegida por versão otimista.

## Autorização

- Membros com permissão `VIEW` consultam o resumo dentro da família.
- Somente proprietários usam `MANAGE_FINANCES` e `SHARE_VETERINARY_SUMMARY`.
- Toda consulta de pet, despesa, registro, ocorrência, mídia ou link inclui o `householdId`; IDs externos nunca ampliam o escopo.

## Persistência

A migração `V17__create_veterinary_summary_and_finance.sql`:

- adiciona custo estimado opcional e moeda a `care_plan` e `care_occurrence`;
- cria `expense`, com valor positivo, moeda ISO, autoria, timestamps e versão;
- cria `veterinary_share`, com hash único, escopo, expiração, revogação e auditoria de acesso;
- adiciona índices para consultas por família, pet, data, status e expiração;
- remove despesas e links automaticamente quando seu pet/família é excluído.

Hibernate continua em `ddl-auto: validate` e `open-in-view: false`. As consultas agregadas de custos clínicos são executadas em lote, sem uma consulta por registro.

## API

Rotas autenticadas:

- `GET /api/v1/pets/{petId}/veterinary-summary?from=&to=`
- `POST|GET /api/v1/veterinary-shares`
- `DELETE /api/v1/veterinary-shares/{id}?version=`
- `POST|PUT|GET|DELETE /api/v1/expenses`
- `GET /api/v1/finances/overview?from=&to=&forecastTo=&petId=`

Rotas públicas:

- `POST /api/v1/public/veterinary-summary`
- `POST /api/v1/public/veterinary-summary/attachment-url`

Os períodos do resumo e da visão financeira são limitados a 366 dias; a listagem operacional de despesas aceita até cinco anos. Valores futuros são limitados a 90 dias e as coleções possuem limites explícitos para impedir respostas sem controle.

## Implantação e rollback

1. Fazer backup do banco e aplicar a V17 antes de publicar o backend.
2. Publicar o backend; os novos campos são opcionais e compatíveis com o frontend anterior.
3. Publicar o frontend e validar resumo privado, criação/revogação de link e finanças com uma família de homologação.
4. Monitorar respostas 429 dos endpoints públicos, tempo das consultas e quantidade de links ativos.

O rollback de aplicação pode voltar ao Ciclo 4 sem remover colunas ou tabelas. A migração é expansiva; a remoção física dos novos objetos deve ser feita somente em uma migração posterior e após confirmar que não existem dados a preservar.

## Verificação

- Testes de serviço cobrem autorização, minimização padrão, expiração, hash sem token bruto, composição realizado/previsão e restrição por papel.
- O smoke test sobe a aplicação completa com PostgreSQL/pgvector, executa todas as migrations e valida o schema Hibernate.
- O frontend possui testes dos contratos HTTP, build de produção e verificação visual responsiva e de impressão.
