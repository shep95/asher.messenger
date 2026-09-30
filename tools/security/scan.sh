#!/usr/bin/env bash
# Re-run the dependency and secret scans described in docs/security-audit.md.
#
#   tools/security/scan.sh [out-dir]
#
# Needs: cargo-audit (cargo install cargo-audit), pnpm, trivy, gitleaks, python3, a JDK for the
# Maven services, and a clone of https://github.com/github/advisory-database (set ADVISORY_DB;
# `git clone --depth 1 --filter=blob:none --sparse` then `git sparse-checkout set advisories/github-reviewed`).
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/out/security}; mkdir -p "$OUT"
ADVISORY_DB=${ADVISORY_DB:-$OUT/advisory-database}
have() { command -v "$1" >/dev/null 2>&1; }

echo "== cargo audit"
if have cargo-audit; then
  for lock in libs/libsignal/Cargo.lock libs/ringrtc/Cargo.lock services/calling/Cargo.lock services/svr2/host/rustclient/Cargo.lock; do
    echo "-- $lock"; (cd "$ROOT/$(dirname "$lock")" && cargo audit 2>&1 | tail -n 20) | tee "$OUT/cargo-audit-$(echo "$lock" | tr / _).txt" | grep -E "Vulnerabilit|error|warning: [0-9]" || true
  done
else echo "cargo-audit not installed"; fi

echo "== pnpm audit (desktop, production dependencies)"
if have pnpm; then (cd "$ROOT/clients/desktop" && pnpm audit --prod 2>&1 | tail -n 30 | tee "$OUT/pnpm-audit-prod.txt"); else echo "pnpm not installed"; fi

echo "== trivy (Go, CocoaPods, Bundler, npm, Cargo lockfiles; offline Maven)"
if have trivy; then
  for t in clients/ios clients/desktop services/svr2 services/calling libs; do
    trivy fs --scanners vuln --offline-scan --skip-dirs node_modules,.git,target,.cargotarget --skip-files '**/pom.xml' \
      --format table --output "$OUT/trivy-$(basename "$t").txt" "$ROOT/$t" >/dev/null 2>&1 && grep -E "Total:|│" "$OUT/trivy-$(basename "$t").txt" | head -n 40
  done
else echo "trivy not installed"; fi

echo "== Maven runtime trees vs GitHub advisory database"
if [ -d "$ADVISORY_DB/advisories" ] && have python3; then
  for svc in server registration storage; do
    dir="$ROOT/services/$svc"; extra=""; [ "$svc" = server ] && extra="-Pexclude-spam-filter -pl service -am"
    (cd "$dir" && ./mvnw -B -q $extra dependency:list -DoutputFile="$OUT/$svc-deps.txt" -DincludeScope=runtime >/dev/null 2>&1) || echo "-- $svc: dependency resolution failed (private artifacts?)"
    [ -f "$OUT/$svc-deps.txt" ] && { echo "-- $svc"; python3 "$ROOT/tools/security/osvmatch.py" "$ADVISORY_DB" Maven "$OUT/$svc-deps.txt" | tee "$OUT/$svc-advisories.txt"; }
  done
  python3 - "$ROOT" "$OUT" <<'PY'
import re,sys,tomllib,glob
root,out=sys.argv[1],sys.argv[2]
t=tomllib.load(open(f'{root}/clients/android/gradle/libs.versions.toml','rb')); vers=t.get('versions',{}); coords=set()
for l in t.get('libraries',{}).values():
    if isinstance(l,str):
        p=l.split(':'); len(p)==3 and coords.add(tuple(p)); continue
    g,a=(l['module'].split(':') if 'module' in l else (l.get('group'),l.get('name'))); v=l.get('version')
    if isinstance(v,dict): v=vers.get(v.get('ref'))
    if g and a and v: coords.add((g,a,str(v)))
open(f'{out}/android-deps.txt','w').write("\n".join(f"{g}:{a}:jar:{v}" for g,a,v in sorted(coords))+"\n")
PY
  echo "-- android (declared catalog)"; python3 "$ROOT/tools/security/osvmatch.py" "$ADVISORY_DB" Maven "$OUT/android-deps.txt" | tee "$OUT/android-advisories.txt"
else echo "advisory database not found at $ADVISORY_DB (set ADVISORY_DB)"; fi

echo "== gitleaks"
if have gitleaks; then gitleaks dir "$ROOT" --no-banner --redact=60 --report-format json --report-path "$OUT/gitleaks.json" --exit-code 0 2>&1 | tail -n 3; else echo "gitleaks not installed"; fi
echo "Reports in $OUT"
