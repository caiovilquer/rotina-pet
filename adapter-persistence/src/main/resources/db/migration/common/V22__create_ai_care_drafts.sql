CREATE TABLE ai_care_draft (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    household_id UUID NOT NULL,
    actor_tutor_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    external_message_id VARCHAR(255),
    status VARCHAR(24) NOT NULL,
    input_type VARCHAR(16) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    structured_payload JSONB NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    missing_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    field_provenance JSONB NOT NULL DEFAULT '{}'::jsonb,
    provider VARCHAR(80),
    model VARCHAR(120),
    prompt_version VARCHAR(80) NOT NULL,
    plan_id UUID,
    failure_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_ai_care_draft_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_care_draft_actor FOREIGN KEY (actor_tutor_id) REFERENCES tutor(id),
    CONSTRAINT ck_ai_care_draft_channel CHECK (channel IN ('WEB', 'WHATSAPP')),
    CONSTRAINT ck_ai_care_draft_status CHECK (status IN ('PROCESSING', 'NEEDS_INPUT', 'READY', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'FAILED')),
    CONSTRAINT ck_ai_care_draft_input_type CHECK (input_type IN ('TEXT', 'AUDIO', 'IMAGE', 'PDF')),
    CONSTRAINT ck_ai_care_draft_hash CHECK (input_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_ai_care_draft_expiration CHECK (expires_at > created_at),
    CONSTRAINT ck_ai_care_draft_confirmation CHECK (
        (status = 'CONFIRMED' AND plan_id IS NOT NULL AND confirmed_at IS NOT NULL)
        OR (status <> 'CONFIRMED' AND plan_id IS NULL AND confirmed_at IS NULL)
    )
);

CREATE TABLE ai_care_draft_action (
    id UUID PRIMARY KEY,
    draft_id UUID NOT NULL,
    request_id UUID UNIQUE,
    actor_tutor_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    action VARCHAR(24) NOT NULL,
    previous_status VARCHAR(24),
    new_status VARCHAR(24) NOT NULL,
    previous_version BIGINT,
    new_version BIGINT,
    happened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ai_draft_action_draft FOREIGN KEY (draft_id) REFERENCES ai_care_draft(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_draft_action_actor FOREIGN KEY (actor_tutor_id) REFERENCES tutor(id),
    CONSTRAINT ck_ai_draft_action_channel CHECK (channel IN ('WEB', 'WHATSAPP')),
    CONSTRAINT ck_ai_draft_action_type CHECK (action IN ('GENERATED', 'EXTRACTED', 'CORRECTED', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'FAILED', 'FEEDBACK'))
);

CREATE TABLE ai_interaction (
    id UUID PRIMARY KEY,
    draft_id UUID,
    household_id UUID NOT NULL,
    actor_tutor_id BIGINT NOT NULL,
    operation VARCHAR(80) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    model VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_millis BIGINT NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    error_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ai_interaction_draft FOREIGN KEY (draft_id) REFERENCES ai_care_draft(id) ON DELETE SET NULL,
    CONSTRAINT fk_ai_interaction_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_interaction_actor FOREIGN KEY (actor_tutor_id) REFERENCES tutor(id),
    CONSTRAINT ck_ai_interaction_channel CHECK (channel IN ('WEB', 'WHATSAPP')),
    CONSTRAINT ck_ai_interaction_outcome CHECK (outcome IN ('SUCCESS', 'PROVIDER_ERROR', 'INVALID_OUTPUT', 'DISABLED')),
    CONSTRAINT ck_ai_interaction_usage CHECK (
        latency_millis >= 0 AND (input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0)
    )
);

CREATE TABLE assistant_feedback (
    id UUID PRIMARY KEY,
    draft_id UUID NOT NULL,
    household_id UUID NOT NULL,
    actor_tutor_id BIGINT NOT NULL,
    positive BOOLEAN NOT NULL,
    corrected_fields JSONB NOT NULL DEFAULT '[]'::jsonb,
    reason VARCHAR(80),
    comment VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_assistant_feedback_draft FOREIGN KEY (draft_id) REFERENCES ai_care_draft(id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_feedback_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_feedback_actor FOREIGN KEY (actor_tutor_id) REFERENCES tutor(id)
);

ALTER TABLE care_plan ADD COLUMN source_draft_id UUID;
ALTER TABLE care_plan ADD CONSTRAINT uk_care_plan_source_draft UNIQUE (source_draft_id);
ALTER TABLE care_plan ADD CONSTRAINT fk_care_plan_source_draft
    FOREIGN KEY (source_draft_id) REFERENCES ai_care_draft(id);
ALTER TABLE ai_care_draft ADD CONSTRAINT fk_ai_care_draft_plan
    FOREIGN KEY (plan_id) REFERENCES care_plan(id);

CREATE INDEX idx_ai_care_draft_household_updated ON ai_care_draft(household_id, updated_at DESC);
CREATE INDEX idx_ai_care_draft_expiration ON ai_care_draft(status, expires_at);
CREATE INDEX idx_ai_care_draft_action_draft_time ON ai_care_draft_action(draft_id, happened_at);
CREATE INDEX idx_ai_interaction_operation_time ON ai_interaction(operation, created_at);
CREATE INDEX idx_assistant_feedback_draft_time ON assistant_feedback(draft_id, created_at);
