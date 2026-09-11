#!/bin/bash
# Applies the MPL 2.0 source header to every Java file under src/ that
# does not already carry it. Idempotent: files already containing the
# header are left untouched.
set -euo pipefail
cd "$(dirname "$0")/.."

HEADER='/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */'

count=0
while IFS= read -r -d '' f; do
  if ! grep -q "Mozilla Public License, v. 2.0" "$f"; then
    tmp="$(mktemp)"
    printf '%s\n' "$HEADER" > "$tmp"
    cat "$f" >> "$tmp"
    mv "$tmp" "$f"
    count=$((count + 1))
  fi
done < <(find src -name '*.java' -print0)

echo "license headers applied to $count file(s)"
