-- users present in db1 (source of truth)
--   alice, bob, carol share ids with db2 (bob/carol diverge -> UPDATE)
--   dave (id 4) exists ONLY in db1                    -> INSERT
INSERT INTO users (id, name, email, age) VALUES
    (1, 'alice', 'alice@x.com', 30),
    (2, 'bob',   'bob@old.com', 40),
    (3, 'carol', 'carol@x.com', 22),
    (4, 'dave',  'dave@x.com',   34);