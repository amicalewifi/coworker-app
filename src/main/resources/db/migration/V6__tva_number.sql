-- V6: Numéro de TVA CHE pour les membres entreprises

ALTER TABLE members
    ADD COLUMN IF NOT EXISTS tva_number TEXT;
