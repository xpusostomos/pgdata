-- all_types in db1 (source of truth).
--   id 1 : canonical, EVERY JDBC type set (db2 has a divergent copy -> UPDATE)
--   id 2 : present ONLY in db1                       -> INSERT
--   id 5 : identical in both (must produce NO output)
--   NOTE: value t in row 1 deliberately contains a single-quote, a double
--         quote, and a backslash to exercise string-literal escaping.
INSERT INTO all_types
    (id, b, si, i, bi, r, dp, n, t, d, c, dt, tm, tsz, ts, ba, u, jb) VALUES
  (1,
   TRUE, 645, 100000, 99999999999, 3.14, 2.718281828459045, 1233.1234,
   'O''Brien said "hi" a\b', 'var-1', 'qiux',
   DATE '2021-06-15', TIME '13:14:00',
   TIMESTAMPTZ '2021-06-15 13:14:15.123456+00',
   TIMESTAMP  '2021-06-15 13:14:15.654321',
   decode('0001ff', 'hex'),
   '12345678-1234-1234-1234-123456789012',
   '{"k":1,"arr":[10,20]}'::jsonb),
  (2,
   FALSE, 1, 2, 3, 4.5, 5.5, 7.0000,
   'second row', 'var-2', 'd',
   DATE '2020-06-15', TIME '00:00:00',
   TIMESTAMPTZ '2020-06-15 00:00:00+00',
   TIMESTAMP  '2020-06-15 00:00:00',
   decode('00', 'hex'),
   NULL, NULL),
  (5,
   TRUE, 9, 9, 9, 1.1, 2.2, 3.0000,
   'shared', 'var-5', 'eins',
   DATE '2019-05-01', TIME '01:02:03',
   TIMESTAMPTZ '2019-05-05 01:02:03+00',
   TIMESTAMP  '2019-05-05 01:02:03',
   NULL, NULL, NULL);