#!/bin/sh
# Applies the Quest port's ShiftGlue SDK patches (idempotent).
set -e
cd "$(dirname "$0")/../thirdparty/shiftglue-sdk"
for p in ../../quest/patches/*.patch; do
  if git apply --reverse --check "$p" 2>/dev/null; then
    echo "already applied: $(basename "$p")"
  else
    git apply "$p" && echo "applied: $(basename "$p")"
  fi
done
