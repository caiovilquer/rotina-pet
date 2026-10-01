CREATE TABLE assistant_answer (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    actor_tutor_id BIGINT NOT NULL,
    pet_id BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    question_hash VARCHAR(64) NOT NULL,
    insufficient_evidence BOOLEAN NOT NULL,
    citation_count INTEGER NOT NULL,
    provider VARCHAR(80),
    model VARCHAR(120),
    prompt_version VARCHAR(80),
    corpus_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_assistant_answer_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_answer_actor FOREIGN KEY (actor_tutor_id) REFERENCES tutor(id),
    CONSTRAINT fk_assistant_answer_pet FOREIGN KEY (pet_id) REFERENCES pet(id) ON DELETE CASCADE,
    CONSTRAINT ck_assistant_answer_kind CHECK (kind IN ('STRUCTURED', 'RAG', 'REFUSAL')),
    CONSTRAINT ck_assistant_answer_hash CHECK (question_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_assistant_answer_citations CHECK (citation_count >= 0)
);

ALTER TABLE assistant_feedback ALTER COLUMN draft_id DROP NOT NULL;
ALTER TABLE assistant_feedback ADD COLUMN answer_id UUID;
ALTER TABLE assistant_feedback ADD CONSTRAINT fk_assistant_feedback_answer
    FOREIGN KEY (answer_id) REFERENCES assistant_answer(id) ON DELETE CASCADE;
ALTER TABLE assistant_feedback ADD CONSTRAINT ck_assistant_feedback_target
    CHECK ((draft_id IS NOT NULL AND answer_id IS NULL) OR (draft_id IS NULL AND answer_id IS NOT NULL));

CREATE TABLE knowledge_source (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    pet_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    resource_id UUID NOT NULL,
    resource_version VARCHAR(80) NOT NULL,
    title VARCHAR(180) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    language VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    extractor_version VARCHAR(80) NOT NULL,
    chunker_version VARCHAR(80) NOT NULL,
    embedding_model VARCHAR(120) NOT NULL,
    error_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_knowledge_source_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_source_pet FOREIGN KEY (pet_id) REFERENCES pet(id) ON DELETE CASCADE,
    CONSTRAINT uk_knowledge_source_resource UNIQUE (household_id, type, resource_id),
    CONSTRAINT ck_knowledge_source_type CHECK (type IN ('HEALTH_RECORD', 'HEALTH_MEASUREMENT', 'HEALTH_ATTACHMENT', 'CARE_PLAN', 'VETERINARY_SUMMARY_NOTE')),
    CONSTRAINT ck_knowledge_source_status CHECK (status IN ('PENDING', 'INDEXING', 'READY', 'FAILED', 'STALE', 'DELETED')),
    CONSTRAINT ck_knowledge_source_checksum CHECK (checksum ~ '^[a-f0-9]{64}$')
);

CREATE TABLE knowledge_index_outbox (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL,
    operation VARCHAR(16) NOT NULL,
    dedupe_key VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_knowledge_index_source FOREIGN KEY (source_id) REFERENCES knowledge_source(id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_index_operation CHECK (operation IN ('UPSERT', 'DELETE', 'REINDEX')),
    CONSTRAINT ck_knowledge_index_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')),
    CONSTRAINT ck_knowledge_index_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_assistant_answer_household_created ON assistant_answer(household_id, created_at DESC);
CREATE INDEX idx_assistant_feedback_answer_time ON assistant_feedback(answer_id, created_at) WHERE answer_id IS NOT NULL;
CREATE INDEX idx_knowledge_source_pet_status ON knowledge_source(household_id, pet_id, status, updated_at DESC);
CREATE INDEX idx_knowledge_index_pending ON knowledge_index_outbox(status, next_attempt_at, created_at);
