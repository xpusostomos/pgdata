#!/usr/bin/env bash
# Populate db2 (pgtest_db2) -- a divergent copy.
#   users  -> alice(identical), bob(differs), dave(only db2, pk=3)
#   products -> SKU-A(identical), SKU-B(differs), SKU-C(only db2)
set -euo pipefail
cd "$(dirname "$0")"

PSQL=(psql -v ON_ERROR_STOP=1 -U postgres -d pgtest_db2)

import() { local file="$1"; "${PSQL[@]}" -f "$file" >/dev/null; }

import data/users_db2.sql
import data/products_db2.sql
import data/all_types_db2.sql
import data/enrollments_db2.sql

echo "db2 populated (users, products, all_types)"