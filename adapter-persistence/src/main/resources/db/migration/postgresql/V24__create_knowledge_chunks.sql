-- A V20 pode ter sido aplicada com outro search_path em bases de teste. O tipo
-- fica em um schema estável antes de ser referenciado pelas queries do RAG.
ALTER EXTENSION vector SET SCHEMA public;

CREATE TABLE knowledge_chunk (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    normalized_text TEXT NOT NULL,
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('portuguese', normalized_text)) STORED,
    embedding public.VECTOR(512) NOT NULL,
    page INTEGER,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    embedding_model VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_knowledge_chunk_source FOREIGN KEY (source_id) REFERENCES knowledge_source(id) ON DELETE CASCADE,
    CONSTRAINT uk_knowledge_chunk_source_index UNIQUE (source_id, chunk_index),
    CONSTRAINT ck_knowledge_chunk_page CHECK (page IS NULL OR page > 0),
    CONSTRAINT ck_knowledge_chunk_offsets CHECK (start_offset >= 0 AND end_offset > start_offset),
    CONSTRAINT ck_knowledge_chunk_hash CHECK (content_hash ~ '^[a-f0-9]{64}$')
);

CREATE INDEX idx_knowledge_chunk_fts ON knowledge_chunk USING GIN(search_vector);
CREATE INDEX idx_knowledge_chunk_source ON knowledge_chunk(source_id, chunk_index);
