-- Ciclo 4: família explícita como fronteira de autorização. UUIDs de backfill
-- são determinísticos e preservam compatibilidade com os registros existentes.
CREATE TABLE household (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(100) NOT NULL,
    created_by_tutor_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_household_creator FOREIGN KEY (created_by_tutor_id) REFERENCES tutor(id),
    CONSTRAINT ck_household_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 100)
);

CREATE TABLE household_member (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    household_id UUID NOT NULL,
    tutor_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_household_member_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_household_member_tutor FOREIGN KEY (tutor_id) REFERENCES tutor(id) ON DELETE CASCADE,
    CONSTRAINT uk_household_member UNIQUE (household_id, tutor_id),
    CONSTRAINT ck_household_member_role CHECK (role IN ('OWNER', 'CAREGIVER', 'VIEWER'))
);

ALTER TABLE tutor ADD COLUMN default_household_id UUID;

INSERT INTO household (id, name, created_by_tutor_id, created_at, updated_at)
SELECT
    CAST(CONCAT('10000000-0000-0000-0000-', RIGHT(CONCAT('000000000000', CAST(t.id AS VARCHAR)), 12)) AS UUID),
    LEFT(CONCAT('Família de ', t.first_name), 100), t.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM tutor t;

INSERT INTO household_member (id, household_id, tutor_id, role, joined_at)
SELECT
    CAST(CONCAT('20000000-0000-0000-0000-', RIGHT(CONCAT('000000000000', CAST(t.id AS VARCHAR)), 12)) AS UUID),
    CAST(CONCAT('10000000-0000-0000-0000-', RIGHT(CONCAT('000000000000', CAST(t.id AS VARCHAR)), 12)) AS UUID),
    t.id, 'OWNER', CURRENT_TIMESTAMP
FROM tutor t;

UPDATE tutor SET default_household_id = CAST(
    CONCAT('10000000-0000-0000-0000-', RIGHT(CONCAT('000000000000', CAST(id AS VARCHAR)), 12)) AS UUID
);
ALTER TABLE tutor ADD CONSTRAINT fk_tutor_default_household FOREIGN KEY (default_household_id) REFERENCES household(id);

CREATE TABLE household_invitation (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    household_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    active_key VARCHAR(400) UNIQUE,
    invited_by_tutor_id BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_household_invite_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_household_invite_actor FOREIGN KEY (invited_by_tutor_id) REFERENCES tutor(id),
    CONSTRAINT ck_household_invite_role CHECK (role IN ('CAREGIVER', 'VIEWER')),
    CONSTRAINT ck_household_invite_state CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);

CREATE TABLE household_activity (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    type VARCHAR(32) NOT NULL,
    actor_tutor_id BIGINT,
    target_tutor_id BIGINT,
    pet_id BIGINT,
    care_occurrence_id UUID,
    summary VARCHAR(240) NOT NULL,
    happened_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_household_activity_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_household_activity_actor FOREIGN KEY (actor_tutor_id) REFERENCES tutor(id),
    CONSTRAINT fk_household_activity_target FOREIGN KEY (target_tutor_id) REFERENCES tutor(id),
    CONSTRAINT fk_household_activity_pet FOREIGN KEY (pet_id) REFERENCES pet(id) ON DELETE SET NULL,
    CONSTRAINT fk_household_activity_occurrence FOREIGN KEY (care_occurrence_id) REFERENCES care_occurrence(id) ON DELETE SET NULL,
    CONSTRAINT ck_household_activity_summary CHECK (CHAR_LENGTH(summary) BETWEEN 1 AND 240)
);

CREATE TABLE household_handoff (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    from_tutor_id BIGINT NOT NULL,
    to_tutor_id BIGINT,
    note VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_handoff_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_handoff_from FOREIGN KEY (from_tutor_id) REFERENCES tutor(id),
    CONSTRAINT fk_handoff_to FOREIGN KEY (to_tutor_id) REFERENCES tutor(id),
    CONSTRAINT ck_handoff_note CHECK (CHAR_LENGTH(note) BETWEEN 1 AND 1000)
);

ALTER TABLE pet ADD COLUMN household_id UUID;
UPDATE pet SET household_id = (SELECT t.default_household_id FROM tutor t WHERE t.id = pet.tutor_id);
ALTER TABLE pet ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE pet ADD CONSTRAINT fk_pet_household FOREIGN KEY (household_id) REFERENCES household(id);

ALTER TABLE care_plan ADD COLUMN household_id UUID;
ALTER TABLE care_plan ADD COLUMN critical BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE care_plan ADD COLUMN escalation_delay_minutes INTEGER;
ALTER TABLE care_plan ADD COLUMN escalation_tutor_id BIGINT;
UPDATE care_plan SET household_id = (SELECT t.default_household_id FROM tutor t WHERE t.id = care_plan.tutor_id);
ALTER TABLE care_plan ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE care_plan ADD CONSTRAINT fk_care_plan_household FOREIGN KEY (household_id) REFERENCES household(id);
ALTER TABLE care_plan ADD CONSTRAINT fk_care_plan_escalation_tutor FOREIGN KEY (escalation_tutor_id) REFERENCES tutor(id);
ALTER TABLE care_plan ADD CONSTRAINT ck_care_plan_escalation CHECK (
    (critical = FALSE AND escalation_delay_minutes IS NULL AND escalation_tutor_id IS NULL)
    OR (critical = TRUE AND escalation_delay_minutes BETWEEN 15 AND 10080 AND escalation_tutor_id IS NOT NULL)
);

ALTER TABLE care_occurrence ADD COLUMN household_id UUID;
ALTER TABLE care_occurrence ADD COLUMN responsible_tutor_id BIGINT;
ALTER TABLE care_occurrence ADD COLUMN critical BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE care_occurrence ADD COLUMN escalation_delay_minutes INTEGER;
ALTER TABLE care_occurrence ADD COLUMN escalation_tutor_id BIGINT;
UPDATE care_occurrence SET
    household_id = (SELECT p.household_id FROM care_plan p WHERE p.id = care_occurrence.plan_id),
    responsible_tutor_id = (SELECT p.responsible_tutor_id FROM care_plan p WHERE p.id = care_occurrence.plan_id);
ALTER TABLE care_occurrence ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE care_occurrence ALTER COLUMN responsible_tutor_id SET NOT NULL;
ALTER TABLE care_occurrence ADD CONSTRAINT fk_care_occurrence_household FOREIGN KEY (household_id) REFERENCES household(id);
ALTER TABLE care_occurrence ADD CONSTRAINT fk_care_occurrence_responsible FOREIGN KEY (responsible_tutor_id) REFERENCES tutor(id);
ALTER TABLE care_occurrence ADD CONSTRAINT fk_care_occurrence_escalation_tutor FOREIGN KEY (escalation_tutor_id) REFERENCES tutor(id);
ALTER TABLE care_occurrence ADD CONSTRAINT ck_care_occurrence_escalation CHECK (
    (critical = FALSE AND escalation_delay_minutes IS NULL AND escalation_tutor_id IS NULL)
    OR (critical = TRUE AND escalation_delay_minutes BETWEEN 15 AND 10080 AND escalation_tutor_id IS NOT NULL)
);

ALTER TABLE health_record ADD COLUMN household_id UUID;
UPDATE health_record SET household_id = (SELECT t.default_household_id FROM tutor t WHERE t.id = health_record.tutor_id);
ALTER TABLE health_record ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE health_record ADD CONSTRAINT fk_health_record_household FOREIGN KEY (household_id) REFERENCES household(id);

ALTER TABLE health_measurement ADD COLUMN household_id UUID;
UPDATE health_measurement SET household_id = (SELECT t.default_household_id FROM tutor t WHERE t.id = health_measurement.tutor_id);
ALTER TABLE health_measurement ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE health_measurement ADD CONSTRAINT fk_health_measurement_household FOREIGN KEY (household_id) REFERENCES household(id);

ALTER TABLE media_asset ADD COLUMN household_id UUID;
UPDATE media_asset SET household_id = (SELECT t.default_household_id FROM tutor t WHERE t.id = media_asset.tutor_id)
WHERE purpose <> 'TUTOR_AVATAR' AND tutor_id IS NOT NULL;
ALTER TABLE media_asset ADD CONSTRAINT fk_media_household FOREIGN KEY (household_id) REFERENCES household(id);
ALTER TABLE media_asset ADD CONSTRAINT ck_media_household_scope CHECK (
    (purpose = 'TUTOR_AVATAR' AND household_id IS NULL)
    OR (purpose IN ('PET_PHOTO', 'HEALTH_ATTACHMENT') AND household_id IS NOT NULL)
);

CREATE TABLE care_escalation_outbox (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    occurrence_id UUID NOT NULL UNIQUE,
    household_id UUID NOT NULL,
    recipient_tutor_id BIGINT NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    pet_name VARCHAR(255) NOT NULL,
    care_title VARCHAR(120) NOT NULL,
    due_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_escalation_occurrence FOREIGN KEY (occurrence_id) REFERENCES care_occurrence(id) ON DELETE CASCADE,
    CONSTRAINT fk_escalation_household FOREIGN KEY (household_id) REFERENCES household(id) ON DELETE CASCADE,
    CONSTRAINT fk_escalation_recipient FOREIGN KEY (recipient_tutor_id) REFERENCES tutor(id),
    CONSTRAINT ck_escalation_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_household_member_tutor ON household_member(tutor_id, household_id);
CREATE INDEX idx_household_invite_active ON household_invitation(household_id, active_key, expires_at);
CREATE INDEX idx_household_activity_recent ON household_activity(household_id, happened_at DESC);
CREATE INDEX idx_household_handoff_recent ON household_handoff(household_id, created_at DESC);
CREATE INDEX idx_pet_household_name ON pet(household_id, name, id);
CREATE INDEX idx_care_plan_household_active ON care_plan(household_id, active);
CREATE INDEX idx_care_occurrence_household_due ON care_occurrence(household_id, due_at);
CREATE INDEX idx_health_record_household_time ON health_record(household_id, occurred_at DESC);
CREATE INDEX idx_health_measurement_household_time ON health_measurement(household_id, measured_at DESC);
CREATE INDEX idx_media_household_status ON media_asset(household_id, status);
CREATE INDEX idx_escalation_pending ON care_escalation_outbox(sent_at, attempts, created_at);
