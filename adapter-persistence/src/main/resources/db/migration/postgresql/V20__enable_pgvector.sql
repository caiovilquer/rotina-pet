-- A versão 1 do assistente exige pgvector no mesmo PostgreSQL transacional.
-- Falhar nesta migration é intencional: o ambiente não atende ao contrato de
-- implantação e deve ser corrigido antes que chunks/embeddings sejam criados.
CREATE EXTENSION IF NOT EXISTS vector;
