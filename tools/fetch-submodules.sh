#!/usr/bin/env bash
# The vendored snapshots do not include upstream git submodules (their directories are empty).
# This clones each one, at depth 1, into place, using the .gitmodules file each component ships.
# Needed for: iOS (Pods, backup test vectors), CDSI and SVR2 enclave builds (libsodium, noise-c, ...).
#
#   tools/fetch-submodules.sh                  # all components
#   tools/fetch-submodules.sh clients/ios      # one component directory
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
dirs=("$@"); [ ${#dirs[@]} -gt 0 ] || dirs=(clients/ios services/cdsi services/svr2 services/server)
for d in "${dirs[@]}"; do
  gm="$ROOT/$d/.gitmodules"; [ -f "$gm" ] || { echo "no .gitmodules in $d"; continue; }
  git config -f "$gm" --get-regexp '^submodule\..*\.path$' | while read -r key path; do
    name=${key#submodule.}; name=${name%.path}
    url=$(git config -f "$gm" --get "submodule.$name.url")
    case "$url" in REDACTED|"") echo "skip $d/$path (private upstream submodule)"; continue ;; esac
    if [ -e "$ROOT/$d/$path/.git" ] || [ -n "$(ls -A "$ROOT/$d/$path" 2>/dev/null)" ]; then echo "have $d/$path"; continue; fi
    echo "==> $d/$path <- $url"
    git clone --quiet --depth 1 "$url" "$ROOT/$d/$path"
  done
done
echo "Note: these clones are ignored by git (see .gitignore); re-run after a fresh checkout."
