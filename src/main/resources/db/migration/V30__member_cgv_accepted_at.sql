-- Timestamp d'acceptation des Conditions Générales de Vente lors de
-- l'inscription. NULL pour les membres antérieurs à cette migration ;
-- imposé en application (RegisterController) pour toute nouvelle création.

ALTER TABLE members ADD COLUMN cgv_accepted_at TIMESTAMP NULL;
