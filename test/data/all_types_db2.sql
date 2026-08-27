-- all_types in db2 (divergent copy).
--   id 1 : same id as db1 but DIFFERS in n, t, tsz, ts, u, jb  -> UPDATE (those cols)
--   id 3 : present ONLY in db2                                 -> DELETE
--   id 5 : identical to db1 (must produce NO output)
--   db1 has no id 3; db1 has id 2 which is absent here (INSERT in the other pass)
INSERT INTO all_types
    (id, b, si, i, bi, r, dp, n, t, d, c, dt, tm, tsz, ts, ba, u, jb) VALUES
  (1,
   TRUE, 645, 100000, 99999999999, 3.14, 2.718281828459045, 9876.5432,
   'different value', 'var-1', 'qiux',
   DATE '2021-09-09', TIME '13:14:00',
   TIMESTAMPTZ '2019-12-31 23:59:59+00',
   TIMESTAMP  '2019-12-31 23:59:59',
   decode('0001ff', 'hex'),
   'aaaaaaaa-1234-1234-1234-123456789012',
   '{"z":9}'::jsonb),
  (3,
   FALSE, 3, 4, 5, 6.6, 7.7, 8.8800,
   'only in db2', 'var-3', 'z',
   DATE '2022-01-01', TIME '11:22:33',
   TIMESTAMPTZ '2022-01-01 11:22:33+00',
   TIMESTAMP  '2022-01-01 11:22:33',
   decode('ffee', 'hex'),
   'bbbbbbbb-1234-1234-1234-123456789012',
   '{"only":2}'::jsonb),
  (5,
   TRUE, 9, 9, 9, 1.1, 2.2, 3.0000,
   'shared', 'var-5', 'eins',
   DATE '2019-05-01', TIME '01:02:03',
   TIMESTAMPTZ '2019-05-05 01:02:03+00',
   TIMESTAMP  '2019-05-05 01:02:03',
   NULL, NULL, NULL);