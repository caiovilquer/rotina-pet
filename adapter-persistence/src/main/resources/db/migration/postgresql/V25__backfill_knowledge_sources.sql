-- Fontes preexistentes entram no mesmo pipeline assíncrono. Imagens não são
-- incluídas nesta fase porque ainda exigem OCR/visão; apenas notas e PDFs.
INSERT INTO knowledge_source (
    id, household_id, pet_id, type, resource_id, resource_version, title, checksum, language,
    status, extractor_version, chunker_version, embedding_model, error_code, created_at, updated_at
)
SELECT
    md5('knowledge:' || r.household_id || ':HEALTH_RECORD:' || r.id)::uuid,
    r.household_id,
    r.pet_id,
    'HEALTH_RECORD',
    r.id,
    r.version::text,
    r.title,
    encode(sha256(convert_to(concat_ws(chr(31), r.type, r.occurred_at, r.title, r.notes,
        r.product_name, r.dosage, r.batch_number, r.professional_name, r.clinic_name), 'UTF8')), 'hex'),
    'pt-BR',
    'PENDING',
    'health-record-v1',
    'semantic-chunker-v1',
    'pending',
    NULL,
    r.created_at,
    r.updated_at
FROM health_record r
ON CONFLICT (household_id, type, resource_id) DO NOTHING;

INSERT INTO knowledge_source (
    id, household_id, pet_id, type, resource_id, resource_version, title, checksum, language,
    status, extractor_version, chunker_version, embedding_model, error_code, created_at, updated_at
)
SELECT
    md5('knowledge:' || m.household_id || ':HEALTH_ATTACHMENT:' || m.id)::uuid,
    m.household_id,
    m.pet_id,
    'HEALTH_ATTACHMENT',
    m.id,
    m.version::text,
    m.original_filename,
    lower(m.checksum_sha256),
    'pt-BR',
    'PENDING',
    'pdfbox-v1',
    'semantic-chunker-v1',
    'pending',
    NULL,
    m.created_at,
    COALESCE(m.ready_at, m.created_at)
FROM media_asset m
JOIN health_record_attachment a ON a.media_asset_id = m.id
JOIN health_record r ON r.id = a.health_record_id
WHERE m.purpose = 'HEALTH_ATTACHMENT'
  AND m.status = 'READY'
  AND m.content_type = 'application/pdf'
  AND m.household_id = r.household_id
  AND m.pet_id = r.pet_id
ON CONFLICT (household_id, type, resource_id) DO NOTHING;

INSERT INTO knowledge_index_outbox (
    id, source_id, operation, dedupe_key, status, attempts, next_attempt_at, created_at, updated_at
)
SELECT
    md5('knowledge-backfill:' || s.id)::uuid,
    s.id,
    'UPSERT',
    'backfill:' || s.id || ':' || s.checksum,
    'PENDING',
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM knowledge_source s
WHERE s.status = 'PENDING'
  AND s.type IN ('HEALTH_RECORD', 'HEALTH_ATTACHMENT')
ON CONFLICT (dedupe_key) DO NOTHING;
