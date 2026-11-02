-- Keep existing handles, memberships and history; reserve names irrespective of case.
ALTER TABLE accounts DROP CONSTRAINT account_handle_format;
ALTER TABLE accounts ADD CONSTRAINT account_handle_format
    CHECK (handle ~ '^[A-Za-z][A-Za-z0-9-]{2,23}$');
ALTER TABLE accounts DROP CONSTRAINT accounts_handle_key;
CREATE UNIQUE INDEX accounts_handle_identity ON accounts (lower(handle));
