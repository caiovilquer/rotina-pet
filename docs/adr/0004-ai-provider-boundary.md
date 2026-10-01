# ADR-004 — Provider de IA isolado por capacidade

- Estado: aceito
- Data: 2026-07-14

## Contexto

Extração de rascunhos é probabilística, muda por razões diferentes do domínio
e pode futuramente usar fornecedores com políticas, contratos e falhas
distintas. Espalhar payloads ou SDKs de fornecedor pela aplicação tornaria
prompt, autorização, retry e observabilidade difíceis de evoluir com segurança.

## Decisão

A aplicação depende do `CareInstructionExtractorPort`, um contrato estreito que
recebe contexto autorizado e devolve campos, evidências, ausências, avisos e
proveniência. O módulo `adapter-ai` implementa essa capacidade e não importa
outros adapters. Prompt e JSON Schema são artefatos versionados; fornecedor e
modelo aparecem somente em configuração e telemetria.

O provider local determinístico continua sendo o baseline de desenvolvimento e
CI. Staging também pode selecionar o adapter OpenAI pelo mesmo conjunto de
ports. A seleção aceita exatamente um provider e falha no startup para valores
inválidos ou credenciais ausentes com capacidades ativas; uma falha remota
nunca troca silenciosamente para o fake. O domínio não acomoda classes do SDK.

## Consequências

- O formulário manual funciona sem o provider.
- Conteúdo do usuário não é gravado em `ai_interaction` nem em logs por padrão;
  o draft guarda apenas SHA-256 do input e o payload mínimo revisável.
- Métricas registram operação, versão, resultado e latência, sem prompt ou
  resposta completos.
- Trocar provider não contorna autorização, validação ou confirmação humana.

## Verificação

- Teste arquitetural proíbe imports entre `adapter-ai` e outros adapters.
- Dataset sintético versionado estabelece o baseline determinístico.
- Testes cobrem falha do provider, abstenção em ambiguidades e fallback manual.
