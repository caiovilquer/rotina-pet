-- Linguagem ubíqua: "specie" é termo de mercado financeiro em inglês (a forma
-- correta é "species"); "race" colidia com EventType.BREED no vocabulário do
-- domínio. A migration preserva os dados existentes.
ALTER TABLE pet RENAME COLUMN specie TO species;
ALTER TABLE pet RENAME COLUMN race TO breed;
