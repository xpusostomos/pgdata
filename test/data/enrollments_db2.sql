-- enrollments in db2 (divergent copy). Composite PK = (student_id, course_id).
--   (1,'MATH')     identical to db1                       -> no statement
--   (2,'ENG')      differs in grade (and term)            -> UPDATE where BOTH pk cols match
--   (4,'PHYS')     present ONLY in db2                    -> DELETE (WHERE on both pk cols)
INSERT INTO enrollments (student_id, course_id, grade, term) VALUES
    (1, 'MATH', 88.5, DATE '2023-01-15'),
    (2, 'ENG',  82.0, DATE '2023-01-30'),
    (4, 'PHYS', 70.0, DATE '2023-03-01');