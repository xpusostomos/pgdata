-- enrollments in db1 (source of truth). Composite PK = (student_id, course_id).
--   (1,'MATH') : identical in both                      -> no statement
--   (2,'ENG')   : present in both, differs in grade     -> UPDATE (WHERE on BOTH pk cols)
--   (3,'HIST')  : present ONLY in db1                   -> INSERT
INSERT INTO enrollments (student_id, course_id, grade, term) VALUES
    (1, 'MATH', 88.5, DATE '2023-01-15'),
    (2, 'ENG',  75.0, DATE '2023-01-20'),
    (3, 'HIST', 91.0, DATE '2023-02-01');