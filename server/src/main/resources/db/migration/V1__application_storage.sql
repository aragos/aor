CREATE TABLE accounts (
    id text PRIMARY KEY,
    handle text NOT NULL UNIQUE,
    display_name text NOT NULL,
    may_create_games boolean NOT NULL DEFAULT false,
    CONSTRAINT account_id_length CHECK (length(id) BETWEEN 1 AND 128),
    CONSTRAINT account_handle_format CHECK (handle ~ '^[a-z][a-z0-9-]{2,23}$'),
    CONSTRAINT account_display_name_length CHECK (length(display_name) BETWEEN 1 AND 60)
);

-- The document contains intrinsic metadata and private pending submissions, never revision history.
-- Listing status/stage are rebuildable projections, not sources of game legality.
CREATE TABLE games (
    id uuid PRIMARY KEY,
    document jsonb NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    storage_version bigint NOT NULL DEFAULT 1,
    listing_status text NOT NULL,
    stage text NOT NULL,
    CHECK (jsonb_typeof(document) = 'object')
);

CREATE INDEX games_status_id ON games (listing_status, id);

-- Each payload is an immutable revision delta, suffix edit, or full upgrade checkpoint.
CREATE TABLE game_revisions (
    game_id uuid NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    revision integer NOT NULL CHECK (revision > 0),
    payload jsonb NOT NULL,
    PRIMARY KEY (game_id, revision),
    CHECK (jsonb_typeof(payload) = 'object')
);

-- Membership/credential data lives here, rather than in a second copy inside the document.
-- needs_move is a rebuildable engine-readiness projection updated with every private submission.
CREATE TABLE game_players (
    game_id uuid NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    seat text NOT NULL,
    account_id text REFERENCES accounts(id),
    credential_hash text,
    needs_move boolean NOT NULL,
    PRIMARY KEY (game_id, seat),
    UNIQUE (game_id, account_id),
    CHECK ((account_id IS NULL) <> (credential_hash IS NULL)),
    CHECK (credential_hash IS NULL OR credential_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX game_players_account_game ON game_players (account_id, game_id);
CREATE INDEX game_players_account_move ON game_players (account_id, game_id) WHERE needs_move;

-- Preserve exact preceding document bytes so the archive name's SHA-256 stays reproducible.
CREATE TABLE game_archives (
    game_id uuid NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    name text NOT NULL,
    contents text NOT NULL,
    PRIMARY KEY (game_id, name)
);

-- No game foreign key: purge receipts survive removal of the game and its archives.
CREATE TABLE administration_audit (
    id uuid PRIMARY KEY,
    at bigint NOT NULL,
    actor text NOT NULL,
    action text NOT NULL,
    target text NOT NULL,
    revision integer NOT NULL,
    outcome text NOT NULL DEFAULT 'COMMITTED'
);

CREATE INDEX administration_audit_target_at ON administration_audit (target, at);
