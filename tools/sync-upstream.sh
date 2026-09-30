#!/usr/bin/env bash
# Re-vendor one or all upstream components as history-free snapshots.
#
#   tools/sync-upstream.sh                 # refresh every component to the commit pinned in upstream/manifest.json
#   tools/sync-upstream.sh --latest        # move every component to upstream's current default branch head
#   tools/sync-upstream.sh Signal-Server   # only that component (name as in the manifest)
#   tools/sync-upstream.sh --latest libsignal Signal-Android
#
# Local patches (libsignal brand overrides, Android brand.properties loader, ...) live on top of the
# vendored tree, so after a sync run `git diff` / re-apply them from the commits listed in docs/patches.md.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MANIFEST="$ROOT/upstream/manifest.json"
LATEST=0; ONLY=()
for arg in "$@"; do
  case "$arg" in
    --latest) LATEST=1 ;;
    -h|--help) sed -n 2,12p "$0"; exit 0 ;;
    *) ONLY+=("$arg") ;;
  esac
done
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

names=$(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1])).keys()))' "$MANIFEST")
for name in $names; do
  if [ ${#ONLY[@]} -gt 0 ]; then
    keep=0; for o in "${ONLY[@]}"; do [ "$o" = "$name" ] && keep=1; done; [ $keep = 1 ] || continue
  fi
  read -r path url branch commit < <(python3 -c 'import json,sys; e=json.load(open(sys.argv[1]))[sys.argv[2]]; print(e["path"], e["url"], e["branch"], e["commit"])' "$MANIFEST" "$name")
  echo "==> $name -> $path"
  if [ $LATEST = 1 ]; then
    git clone --quiet --depth 1 --single-branch "$url" "$WORK/$name"
  else
    git init --quiet "$WORK/$name"
    git -C "$WORK/$name" remote add origin "$url"
    git -C "$WORK/$name" fetch --quiet --depth 1 origin "$commit"
    git -C "$WORK/$name" checkout --quiet FETCH_HEAD
  fi
  new_commit=$(git -C "$WORK/$name" rev-parse HEAD)
  new_date=$(git -C "$WORK/$name" log -1 --format=%cI)
  new_subject=$(git -C "$WORK/$name" log -1 --format=%s)
  # Replace the tree wholesale (keeps the working copy honest: deleted upstream files disappear too),
  # then force-add every upstream-tracked file so nested .gitignore rules cannot drop any.
  rm -rf "${ROOT:?}/$path"; mkdir -p "$ROOT/$path"
  tar -C "$WORK/$name" --exclude=.git -cf - . | tar -C "$ROOT/$path" -xf -
  git -C "$WORK/$name" ls-files -z | (cd "$ROOT/$path" && xargs -0 git add -f --) || true
  # record submodule pins (gitlinks) so tools/fetch-submodules.sh can fetch the exact commits
  python3 - "$ROOT/upstream/submodules.lock.json" "$path" "$WORK/$name" <<'PY'
import json,os,subprocess,sys
lock_path,comp,repo=sys.argv[1:]; lock=json.load(open(lock_path)) if os.path.exists(lock_path) else {}
gm=os.path.join(repo,'.gitmodules'); entries={}
if os.path.exists(gm):
    for line in subprocess.check_output(['git','config','-f',gm,'--get-regexp',r'^submodule\..*\.path$'],text=True).splitlines():
        key,path=line.split(' ',1); sub=key[len('submodule.'):-len('.path')]
        url=subprocess.check_output(['git','config','-f',gm,'--get',f'submodule.{sub}.url'],text=True).strip()
        tree=subprocess.check_output(['git','-C',repo,'ls-tree','HEAD',path],text=True).strip()
        entries[path]={"url":url,"commit":tree.split()[2] if tree else None}
if entries: lock[comp]=entries
else: lock.pop(comp,None)
json.dump(lock,open(lock_path,'w'),indent=2); open(lock_path,'a').write("\n")
PY
  git -C "$ROOT" add -A "$path"
  python3 - "$MANIFEST" "$name" "$new_commit" "$new_date" "$new_subject" <<'PY'
import json,sys
m=json.load(open(sys.argv[1])); e=m[sys.argv[2]]
e.update(commit=sys.argv[3], commit_date=sys.argv[4], commit_subject=sys.argv[5])
json.dump(m, open(sys.argv[1],'w'), indent=2); open(sys.argv[1],'a').write("\n")
PY
  echo "    $commit -> $new_commit ($new_subject)"
done
echo "Done. Review with: git status && git diff --cached --stat"
