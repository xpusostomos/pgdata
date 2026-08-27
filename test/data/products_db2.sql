-- products present in db2 (divergent copy)
--   SKU-A -> identical to db1 (no statement expected)
--   SKU-B -> differs from db1 (price + in_stock)     -> UPDATE from db1 rows
--   SKU-C -> only in db2                            -> DELETE
INSERT INTO products (sku, price, in_stock) VALUES
    ('SKU-A', 9.99,  true),
    ('SKU-B', 29.99, true),
    ('SKU-C', 5.00,  false);