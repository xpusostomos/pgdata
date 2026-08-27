#!/usr/bin/env bash
# Populate db1 (pgtest_db1) -- the source of truth.
#   users  -> alice, bob, carol   (products -> SKU-A, SKU-B)
# Imports the shared data files so the two databases use the same identities/rows.
set -euo pipefail
cd "$(dirname "$0")"

PSQL=(psql -v ON_ERROR_STOP=1 -U postgres -d pgtest_db1)

import() { local file="$1"; "${PSQL[@]}" -f "$file" >/dev/null; }

import data/users_db1.sql
import data/products_db1.sql
import data/all_types_db1.sql
import data/enrollments_db1.sql

echo "db1 populated (users, products, all_types)"
echo "next: ./populate_db2.sh"