create table whatsapp_link_token (
    id uuid primary key,
    tutor_id bigint not null references tutor(id) on delete cascade,
    household_id uuid not null references household(id) on delete cascade,
    business_phone_number_id varchar(30) not null,
    token_hash char(64) not null unique,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz not null,
    constraint ck_whatsapp_link_hash check (token_hash ~ '^[a-f0-9]{64}$')
);

create index idx_whatsapp_link_token_active
    on whatsapp_link_token (tutor_id, business_phone_number_id, expires_at)
    where consumed_at is null and revoked_at is null;

create table whatsapp_identity (
    id uuid primary key,
    tutor_id bigint not null references tutor(id) on delete cascade,
    business_phone_number_id varchar(30) not null,
    lookup_hmac char(64) not null,
    wa_id_ciphertext bytea not null,
    wa_id_nonce bytea not null,
    encryption_key_version integer not null check (encryption_key_version > 0),
    linked_at timestamptz not null,
    last_seen_at timestamptz not null,
    revoked_at timestamptz,
    constraint ck_whatsapp_identity_lookup check (lookup_hmac ~ '^[a-f0-9]{64}$'),
    constraint ck_whatsapp_identity_nonce check (octet_length(wa_id_nonce) = 12)
);

create unique index uq_whatsapp_identity_active_lookup
    on whatsapp_identity (business_phone_number_id, lookup_hmac)
    where revoked_at is null;

create unique index uq_whatsapp_identity_active_tutor
    on whatsapp_identity (tutor_id, business_phone_number_id)
    where revoked_at is null;

create table whatsapp_conversation (
    id uuid primary key,
    version bigint not null default 0,
    identity_id uuid not null references whatsapp_identity(id) on delete cascade,
    household_id uuid not null references household(id) on delete cascade,
    state varchar(50) not null,
    pending_draft_id uuid references ai_care_draft(id) on delete set null,
    pending_draft_version bigint,
    expires_at timestamptz,
    updated_at timestamptz not null,
    constraint uq_whatsapp_conversation unique (identity_id, household_id),
    constraint ck_whatsapp_pending_draft check ((pending_draft_id is null) = (pending_draft_version is null)),
    constraint ck_whatsapp_conversation_state check (state in (
        'IDLE', 'AWAITING_LINK', 'AWAITING_HOUSEHOLD', 'AWAITING_PET', 'AWAITING_CLARIFICATION',
        'AWAITING_DRAFT_CONFIRMATION', 'AWAITING_COMPLETION_CONFIRMATION'
    ))
);

create table whatsapp_inbox (
    id uuid primary key,
    provider_event_key varchar(300) not null unique,
    provider_message_id varchar(255) not null,
    business_phone_number_id varchar(30) not null,
    sender_lookup_hmac char(64),
    sender_ciphertext bytea,
    sender_nonce bytea,
    sender_key_version integer,
    content_ciphertext bytea,
    content_nonce bytea,
    content_key_version integer,
    event_type varchar(30) not null,
    provider_status varchar(30),
    event_at timestamptz not null,
    received_at timestamptz not null,
    work_status varchar(20) not null default 'PENDING',
    attempts integer not null default 0,
    next_attempt_at timestamptz not null,
    claimed_until timestamptz,
    last_error_code varchar(80),
    processed_at timestamptz,
    constraint ck_whatsapp_sender_cipher check (
        (sender_ciphertext is null and sender_nonce is null and sender_key_version is null) or
        (sender_ciphertext is not null and sender_nonce is not null and sender_key_version is not null)
    ),
    constraint ck_whatsapp_content_cipher check (
        (content_ciphertext is null and content_nonce is null and content_key_version is null) or
        (content_ciphertext is not null and content_nonce is not null and content_key_version is not null)
    ),
    constraint ck_whatsapp_inbox_type check (event_type in ('TEXT', 'INTERACTIVE', 'STATUS')),
    constraint ck_whatsapp_inbox_status check (work_status in ('PENDING', 'PROCESSING', 'DONE', 'FAILED', 'DEAD')),
    constraint ck_whatsapp_inbox_attempts check (attempts >= 0)
);

create index idx_whatsapp_inbox_claim on whatsapp_inbox (work_status, next_attempt_at, received_at);

create table whatsapp_outbox (
    id uuid primary key,
    identity_id uuid not null references whatsapp_identity(id) on delete cascade,
    household_id uuid not null references household(id) on delete cascade,
    dedupe_key varchar(300) not null unique,
    message_type varchar(20) not null,
    content_ciphertext bytea not null,
    content_nonce bytea not null,
    content_key_version integer not null,
    delivery_status varchar(20) not null default 'PENDING',
    provider_message_id varchar(255) unique,
    status_event_at timestamptz,
    attempts integer not null default 0,
    next_attempt_at timestamptz not null,
    claimed_until timestamptz,
    last_error_code varchar(80),
    created_at timestamptz not null,
    sent_at timestamptz,
    updated_at timestamptz not null,
    constraint ck_whatsapp_outbox_type check (message_type in ('TEXT', 'INTERACTIVE')),
    constraint ck_whatsapp_outbox_status check (delivery_status in (
        'PENDING', 'SENDING', 'RETRY', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'DEAD', 'CANCELLED'
    )),
    constraint ck_whatsapp_outbox_nonce check (octet_length(content_nonce) = 12),
    constraint ck_whatsapp_outbox_key_version check (content_key_version > 0),
    constraint ck_whatsapp_outbox_attempts check (attempts >= 0)
);

create index idx_whatsapp_outbox_claim on whatsapp_outbox (delivery_status, next_attempt_at, created_at);
