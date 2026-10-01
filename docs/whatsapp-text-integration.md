# WhatsApp: vínculo e criação de planos por texto

Esta fase integra o RotinaPet à Cloud API oficial do WhatsApp sem colocar a
Meta, números de telefone ou payloads externos dentro do domínio. O webhook
valida a assinatura sobre os bytes originais, normaliza somente eventos de
texto, botão e status e confirma o recebimento depois de persistir o evento na
inbox. A extração por IA e as escritas de negócio acontecem no worker.

## Garantias

- O token de vínculo tem 256 bits, expira em 10 minutos, só pode ser usado uma
  vez e é armazenado apenas como SHA-256.
- O `wa_id` vem exclusivamente de webhook assinado. A busca usa HMAC com
  contexto e o valor recuperável usa AES-256-GCM com AAD; texto temporário da
  inbox/outbox recebe a mesma proteção.
- A chave do evento recebido é única. O `requestId` dos comandos de gerar,
  confirmar e cancelar é derivado dessa chave, portanto reentregas do webhook
  ou do mesmo botão não criam um segundo rascunho/plano.
- Inbox e outbox usam claim com `FOR UPDATE SKIP LOCKED`, lease, backoff e estado
  `DEAD`. Status `sent`, `delivered` e `read` não regridem quando chegam fora de
  ordem.
- A conversa só cria rascunhos. O plano nasce exclusivamente após o botão
  **Confirmar** e continua protegido pelas regras e locks do `CareDraftUseCase`.
- Nenhum log ou métrica inclui telefone, texto, token ou conteúdo do rascunho.

## Configuração local

O provider `fake` não chama a Meta, mas exercita vínculo, inbox, máquina de
estados e outbox. Ative a feature e forneça identificadores e chaves geradas
localmente:

```bash
export WHATSAPP_ENABLED=true
export WHATSAPP_PROVIDER=fake
export META_PHONE_NUMBER_ID=1234567890
export META_BUSINESS_PHONE_NUMBER=5511999999999
export META_WEBHOOK_VERIFY_TOKEN="$(openssl rand -hex 32)"
export META_APP_SECRET="local-signature-secret"
export WHATSAPP_HMAC_KEY_CURRENT="$(openssl rand -base64 32)"
export WHATSAPP_ENCRYPTION_KEY_CURRENT="$(openssl rand -base64 32)"
export WHATSAPP_KEY_VERSION_CURRENT=1
```

Os valores acima são exemplos de comandos, não segredos versionados. Preserve
as chaves de dados em um secret manager: trocar ou perder a chave AES torna os
vínculos existentes ilegíveis.

## Configuração da Meta

1. Crie/acesse o app da Meta, adicione o produto WhatsApp, associe a WABA e
   selecione o número de teste (ou o número aprovado).
2. Configure o callback público como
   `https://SEU_HOST/api/v1/webhooks/whatsapp` e use exatamente o valor de
   `META_WEBHOOK_VERIFY_TOKEN` na verificação.
3. Assine o campo `messages` da WABA. A assinatura é feita uma vez por WABA.
4. Gere um token de system user com `whatsapp_business_messaging` para envio e
   `whatsapp_business_management` para a administração da assinatura.
5. Configure os segredos no ambiente e altere `WHATSAPP_PROVIDER=meta`.

O adapter envia texto e reply buttons para
`POST /{Phone-Number-ID}/messages` e considera a resposta apenas uma aceitação
técnica; entrega e leitura são atualizadas pelos status do webhook. Os exemplos
de contrato usados nos testes seguem a coleção oficial da Meta:

- [Visão geral da plataforma](https://www.postman.com/meta/whatsapp-business-platform/overview)
- [Envio de texto](https://www.postman.com/meta/whatsapp-business-platform/request/8gvd47s/send-text-message)
- [Reply buttons](https://www.postman.com/meta/whatsapp-business-platform/request/ne00kt6/send-reply-button)
- [Notificações de status](https://www.postman.com/meta/whatsapp-business-platform/request/rgtfq23/message-status-update-notifications)
- [Assinaturas de webhook](https://www.postman.com/meta/whatsapp-business-platform/folder/ypn8q0n/webhook-subscriptions)

Variáveis de produção:

| Variável | Uso |
|---|---|
| `WHATSAPP_ENABLED` | Kill switch da integração |
| `WHATSAPP_PROVIDER` | `fake` ou `meta` |
| `META_GRAPH_API_VERSION` | Versão suportada explícita no formato `vNN.N` |
| `META_PHONE_NUMBER_ID` | ID técnico do número na Cloud API |
| `META_BUSINESS_PHONE_NUMBER` | Número em dígitos para o deep link da tela |
| `META_SYSTEM_USER_TOKEN` | Bearer token de envio |
| `META_APP_SECRET` | Validação HMAC do webhook |
| `META_WEBHOOK_VERIFY_TOKEN` | Challenge inicial do callback |
| `WHATSAPP_HMAC_KEY_CURRENT/PREVIOUS` | Lookup com rotação |
| `WHATSAPP_ENCRYPTION_KEY_CURRENT/PREVIOUS` | AES-GCM com rotação |
| `WHATSAPP_KEY_VERSION_CURRENT/PREVIOUS` | Seleção determinística da chave AES |

## Operação

Métricas sem alta cardinalidade:

- `rotinapet.whatsapp.webhook` por resultado;
- `rotinapet.whatsapp.inbox.processed`;
- `rotinapet.whatsapp.outbox.processed`.

Para investigar falhas definitivas, consulte `whatsapp_inbox` e
`whatsapp_outbox` pelo estado `DEAD` e pelos códigos sanitizados. O conteúdo
continua cifrado. Depois de corrigir configuração ou indisponibilidade, a
reexecução deve ser feita por uma rotina operacional que retorne o item a
`FAILED`, sem alterar `provider_event_key` ou `dedupe_key`.
