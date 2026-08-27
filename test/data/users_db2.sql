-- users present in db2 (divergent copy)
--   id1 alice -> identical to db1       (no statement)
--   id2 bob   -> differs (email+age)    -> UPDATE
--   id3       -> ONLY in db2, but db1 has a DIFFERENT user at id3 (carol)
--                => seen as same record -> UPDATE to carol's values
--   id5 -> ONLY in db2 (id absent in db1) -> DELETE
INSERT INTO users (id, name, email, age) VALUES
    (1, 'alice', 'alice@x.com', 30),
    (2, 'bob',   'bob@new.com', 41),
    (3, 'only_db2_row', 'x@x.com', 55),
    (5, 'ghost', 'ghost@x.com',  70);