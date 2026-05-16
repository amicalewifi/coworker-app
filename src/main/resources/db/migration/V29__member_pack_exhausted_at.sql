-- Timestamp posé quand un décompte WiFi fait passer le pack à zéro.
-- Sert au délai de grâce de 30 min avant la révocation effective de l'accès.
-- Remis à NULL lors d'un renouvellement (MemberService.renewPack / create / adjustPack).

ALTER TABLE members ADD COLUMN pack_exhausted_at TIMESTAMP NULL;
