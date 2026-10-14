-- dedupe_display_names.sql -- one-time data repair (NOT a migration) for the display-name rules.
--
-- Display names (users.full_name) are now unique, case-insensitively and trimmed, and must not be
-- blank or contain '@'. This script brings existing rows in line:
--   * a blank, NULL or '@'-containing display name becomes 'Member N' (smallest free N from 1);
--   * in each group of accounts whose display names match, the EARLIEST created account (ties: login
--     name) keeps its name and the others become '<name> 2', '<name> 3', ... using the lowest number
--     not already taken. An account with no created timestamp pre-dates timestamping, so it counts as
--     the oldest (the V002 'admin' seed has none and must keep its name).
-- "Match" is the application's own comparison (ProfileNameRules.displayNameKey): NFKC-normalised,
-- runs of whitespace collapsed to one space, trimmed, lower-cased. The '<name>' in a new name is
-- that normalised form in the account's own spelling ('jake   FEAR' becomes 'jake FEAR 2').
-- A candidate is taken when another account already has it as a display name or as a login name
-- (ignoring case), or when a group has that name. users.wiki_name is left alone.
-- Sessions of a renamed account carry the old name until the next sign-in; read-time identity
-- resolution picks the new one up within a minute.
--
-- It also gives every account with a NULL or blank users.uid a new random uid. The uid is the key in
-- @-mention tokens; without a stored one the row mapper invents a different value on every read, so
-- the account could never be mentioned. Stored uids are never replaced. A stored uid that cannot
-- appear in a mention token (outside [A-Za-z0-9-]{1,64}) is only REPORTED, never changed. So is a
-- display name that equals ANOTHER account's login (ignoring case): counted, never renamed.
--
-- IT WRITES. It refuses to run unless wikantik.dedupe_apply = 'on' is set, so a stray
-- 'psql -f' cannot change data. Run it through bin/db/one-shots/dedupe_display_names.sh, which
-- wraps it in BEGIN ... ROLLBACK (the default dry run) or BEGIN ... COMMIT (--apply) and prints the
-- reports below. Idempotent: once the data conforms it changes nothing.
--
-- Reports (temp tables). They hold display names and counts only; a blank or '@'-containing old
-- name is shown as '(blank)' or '(email-shaped)' so an email address never reaches the terminal, and
-- no report has a login or uid column.
--   dedupe_display_names_report(seq, reason, old_display, new_display)  what was changed
--   dedupe_display_names_findings(seq, subject, problem)                reported only, nothing changed
--
-- Needs a UTF8 database (normalize() requires it) and PostgreSQL 13+ (normalize, gen_random_uuid).
-- Keep this file free of trailing '--' comments and backslashes: the .sh collapses it to one line
-- to pass it as a single psql -c argument, which also lets it travel through 'bin/remote.sh psql'.

DO $dedupe$
DECLARE
    r        record;
    n        integer;
    v_cand   text;
    v_suffix text;
BEGIN
    IF COALESCE( current_setting( 'wikantik.dedupe_apply', true ), '' ) <> 'on' THEN
        RAISE EXCEPTION 'dedupe_display_names.sql changes data: run bin/db/one-shots/dedupe_display_names.sh, or SET wikantik.dedupe_apply = on inside a transaction you control';
    END IF;

    CREATE TEMP TABLE IF NOT EXISTS dedupe_display_names_report (
        seq         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        reason      text NOT NULL,
        old_display text NOT NULL,
        new_display text NOT NULL
    );
    CREATE TEMP TABLE IF NOT EXISTS dedupe_display_names_findings (
        seq     integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        subject text NOT NULL,
        problem text NOT NULL
    );
    TRUNCATE pg_temp.dedupe_display_names_report RESTART IDENTITY;
    TRUNCATE pg_temp.dedupe_display_names_findings RESTART IDENTITY;

    FOR r IN
        SELECT login_name, form
          FROM ( SELECT login_name, created,
                        btrim( regexp_replace( normalize( COALESCE( full_name, '' ), NFKC ), '[[:space:]]+', ' ', 'g' ) ) AS form
                   FROM users ) forms
         WHERE form = '' OR form LIKE '%@%'
         ORDER BY created NULLS FIRST, login_name
    LOOP
        n := 1;
        LOOP
            v_cand := 'Member ' || n;
            EXIT WHEN NOT EXISTS ( SELECT 1 FROM users u
                                    WHERE u.login_name <> r.login_name
                                      AND ( lower( btrim( regexp_replace( normalize( COALESCE( u.full_name, '' ), NFKC ), '[[:space:]]+', ' ', 'g' ) ) ) = lower( v_cand )
                                         OR lower( u.login_name ) = lower( v_cand ) ) )
                  AND NOT EXISTS ( SELECT 1 FROM groups g WHERE lower( g.name ) = lower( v_cand ) );
            n := n + 1;
        END LOOP;
        UPDATE users SET full_name = v_cand, modified = now() WHERE login_name = r.login_name;
        INSERT INTO dedupe_display_names_report ( reason, old_display, new_display )
        VALUES ( CASE WHEN r.form = '' THEN 'blank' ELSE 'email-shaped' END,
                 CASE WHEN r.form = '' THEN '(blank)' ELSE '(email-shaped)' END,
                 v_cand );
    END LOOP;

    FOR r IN
        SELECT login_name, form
          FROM ( SELECT login_name, form,
                        row_number() OVER ( PARTITION BY lower( form ) ORDER BY created NULLS FIRST, login_name ) AS rn
                   FROM ( SELECT login_name, created,
                                 btrim( regexp_replace( normalize( COALESCE( full_name, '' ), NFKC ), '[[:space:]]+', ' ', 'g' ) ) AS form
                            FROM users ) forms ) ranked
         WHERE rn > 1
         ORDER BY lower( form ), rn
    LOOP
        n := 2;
        LOOP
            v_suffix := ' ' || n;
            v_cand := btrim( left( r.form, 64 - length( v_suffix ) ) ) || v_suffix;
            EXIT WHEN NOT EXISTS ( SELECT 1 FROM users u
                                    WHERE u.login_name <> r.login_name
                                      AND ( lower( btrim( regexp_replace( normalize( COALESCE( u.full_name, '' ), NFKC ), '[[:space:]]+', ' ', 'g' ) ) ) = lower( v_cand )
                                         OR lower( u.login_name ) = lower( v_cand ) ) )
                  AND NOT EXISTS ( SELECT 1 FROM groups g WHERE lower( g.name ) = lower( v_cand ) );
            n := n + 1;
        END LOOP;
        UPDATE users SET full_name = v_cand, modified = now() WHERE login_name = r.login_name;
        INSERT INTO dedupe_display_names_report ( reason, old_display, new_display )
        VALUES ( 'duplicate', r.form, v_cand );
    END LOOP;

    UPDATE users SET uid = gen_random_uuid()::text WHERE uid IS NULL OR btrim( uid ) = '';
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n > 0 THEN
        INSERT INTO dedupe_display_names_report ( reason, old_display, new_display )
        VALUES ( 'uid-backfill', n || ' account(s) without a stored uid', '(new uid assigned)' );
    END IF;

    INSERT INTO dedupe_display_names_findings ( subject, problem )
    SELECT btrim( full_name ),
           CASE WHEN length( uid ) > 64 THEN 'longer than 64 characters'
                ELSE 'characters other than letters, digits and hyphen' END
      FROM users
     WHERE uid !~ '^[A-Za-z0-9-]{1,64}$'
     ORDER BY created NULLS FIRST, uid;

    SELECT count(*) INTO n
      FROM users a
     WHERE EXISTS ( SELECT 1 FROM users b
                     WHERE b.login_name <> a.login_name
                       AND lower( b.login_name ) = lower( btrim( regexp_replace( normalize( COALESCE( a.full_name, '' ), NFKC ), '[[:space:]]+', ' ', 'g' ) ) ) );
    IF n > 0 THEN
        INSERT INTO dedupe_display_names_findings ( subject, problem )
        VALUES ( n || ' account(s)', 'display name equals another account''s login (left unchanged)' );
    END IF;
END
$dedupe$;
