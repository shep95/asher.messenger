#!/usr/bin/env bash
# The vendored snapshots do not include upstream git submodules (their directories are empty).
# This fetches each one at the exact commit the upstream superproject pinned (recorded in
# upstream/submodules.lock.json by tools/sync-upstream.sh), so enclave builds stay reproducible
# against the committed MRENCLAVE values instead of pulling whatever HEAD is today.
#
#   tools/fetch-submodules.sh                  # all components
#   tools/fetch-submodules.sh clients/ios      # one component directory
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LOCK="$ROOT/upstream/submodules.lock.json"
[ -f "$LOCK" ] || { echo "missing $LOCK" >&2; exit 1; }
dirs=("$@"); [ ${#dirs[@]} -gt 0 ] || mapfile -t dirs < <(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1])).keys()))' "$LOCK")
for d in "${dirs[@]}"; do
  python3 -c 'import json,sys
for p,v in json.load(open(sys.argv[1])).get(sys.argv[2],{}).items(): print(p, v["url"], v["commit"] or "")' "$LOCK" "$d" | while read -r path url commit; do
    case "$url" in REDACTED|"") echo "skip $d/$path (private upstream submodule)"; continue ;; esac
    dest="$ROOT/$d/$path"
    if [ -e "$dest/.git" ] || [ -n "$(ls -A "$dest" 2>/dev/null)" ]; then echo "have $d/$path"; continue; fi
    if [ -z "$commit" ]; then echo "no pinned commit recorded for $d/$path; refusing to fetch an unpinned HEAD" >&2; continue; fi
    echo "==> $d/$path <- $url @ $commit"
    mkdir -p "$dest"; git init --quiet "$dest"; git -C "$dest" remote add origin "$url"
    git -C "$dest" fetch --quiet --depth 1 origin "$commit" && git -C "$dest" checkout --quiet FETCH_HEAD
  done
done
echo "Note: these checkouts are ignored by git (see .gitignore); re-run after a fresh checkout."
