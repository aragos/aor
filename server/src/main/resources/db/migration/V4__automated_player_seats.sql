-- Existing seats remain human and keep their exact membership/credential identity.
ALTER TABLE game_players ADD COLUMN automated boolean NOT NULL DEFAULT false;
ALTER TABLE game_players DROP CONSTRAINT game_players_check;
ALTER TABLE game_players ADD CONSTRAINT game_players_controller_identity CHECK (
    (automated AND account_id IS NULL AND credential_hash IS NULL)
    OR (NOT automated AND ((account_id IS NULL) <> (credential_hash IS NULL)))
);
