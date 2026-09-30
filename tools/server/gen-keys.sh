#!/usr/bin/env bash
# Generate every cryptographic parameter a new Signal-Server deployment needs, using the server's
# own commands plus GenGenericParams.java. Output goes to a directory you keep OFFLINE.
#
#   tools/server/gen-keys.sh out/keys            # after: (cd services/server && ./mvnw -Pexclude-spam-filter -DskipTests package)
#
# Produces:
#   zkparams.txt      groupsZkConfig.serverPublic (pad with '=' to a multiple of 4) + serverSecret
#   ud-ca.txt         unidentified-delivery CA public key (-> brand.json crypto.ud_trust_roots) and CA private key
#   ud-cert.txt       server certificate + private key for unidentifiedDelivery in config/secrets
#   generic.txt       calling + chat GenericServerSecretParams (public halves -> brand.json)
#   random.txt        32-byte random secrets for every "secret://" the sample bundle lists
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:?usage: gen-keys.sh <output-dir>}; mkdir -p "$OUT"; chmod 700 "$OUT"
JAR=$(ls "$ROOT"/services/server/service/target/TextSecureServer-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1)
[ -n "$JAR" ] || { echo "Build the server first: (cd services/server && ./mvnw -Pexclude-spam-filter -DskipTests package)" >&2; exit 1; }
BUNDLE="$ROOT/services/server/service/config/sample-secrets-bundle.yml"   # any valid bundle satisfies the startup check
J=(java "-Dsecrets.bundle.filename=$BUNDLE" -jar "$JAR")

echo "==> zkgroup server params"
"${J[@]}" zkparams > "$OUT/zkparams.txt"
echo "==> unidentified-delivery CA"
"${J[@]}" certificate --ca > "$OUT/ud-ca.txt"
CA_PRIV=$(grep -i 'private' "$OUT/ud-ca.txt" | awk '{print $NF}')
KEY_ID=$(( (RANDOM << 15 | RANDOM) & 0x7fffffff ))
echo "==> unidentified-delivery server certificate (key id $KEY_ID)"
"${J[@]}" certificate -k "$CA_PRIV" -i "$KEY_ID" > "$OUT/ud-cert.txt"
echo "==> generic server params (calling + chat)"
java -cp "$JAR" "$ROOT/tools/server/GenGenericParams.java" > "$OUT/generic.txt"
echo "==> random 32-byte secrets"
{
  for k in stripe.idempotencyKeyGenerator directoryV2.client.userAuthenticationTokenSharedSecret directoryV2.client.userIdTokenSharedSecret \
           svr2.userAuthenticationTokenSharedSecret svr2.userIdTokenSharedSecret svrb.userAuthenticationTokenSharedSecret svrb.userIdTokenSharedSecret \
           tus.userAuthenticationTokenSharedSecret storageService.userAuthenticationTokenSharedSecret paymentsService.userAuthenticationTokenSharedSecret \
           currentReportingKey.secret currentReportingKey.salt registrationService.collationKeySalt linkDevice.secret \
           foundationDbMessages.versionstampCipherKey.0 registrationWebAuthn.userHandleBlindingSecret; do
    printf '%s: %s\n' "$k" "$(openssl rand -base64 32)"
  done
} > "$OUT/random.txt"
echo
echo "Done. Files in $OUT. Next: copy the PUBLIC halves into brands/<id>/brand.json and the SECRET halves into deploy/server/<id>/secrets-bundle.yml (never commit it)."
