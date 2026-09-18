#!/usr/bin/env bash
#
# Create the OTA signing key. Run this ONCE, ever.
#
#   tools/ota-keygen.sh
#
# ## What this key is, and what it is not
#
# It is NOT the APK signing key. That one is in keystore.properties, its
# fingerprint is baked into the TUNER site's assetlinks.json, and rotating it
# silently breaks TWA verification.
#
# This key signs the OTA *manifest* — the small JSON that tells the car which
# version exists and what its hash is. It exists because the head unit's clock
# is frequently wrong (see docs/07 §1: the bugreport is dated 2006), which means
# HTTPS certificate validation can fail before any payload is fetched. Verifying
# our own signature over the manifest bytes makes correctness independent of
# TLS: a wrong clock can then cost availability, but never integrity.
#
# ## Why RSA-2048 and not Ed25519
#
# java.security gained Ed25519 in API 33. The head unit is API 27. ECDSA works
# at 27 but adds a DER-encoding failure mode for nothing. SHA256withRSA has been
# in Android since API 1, verification is one small-exponent modexp, and the
# tooling is `openssl dgst`.
set -uo pipefail

KEY="${OTA_KEY:-$HOME/.e46m3/ota-rsa2048-private.pem}"
PUB_PEM="${OTA_PUB:-tools/ota_public_key.pem}"
PUB_DER="${OTA_PUB_DER:-app-launcher/app/src/main/res/raw/ota_public_key.der}"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m  %s\n' "$*"; }
bad() { printf '   \033[31mNG\033[0m  %s\n' "$*"; }

command -v openssl >/dev/null 2>&1 || { bad "no openssl"; exit 1; }

# ── The private key ──────────────────────────────────────────────────────────
# Refuses to overwrite. Losing this key means every car in the field stops
# accepting updates until it is re-flashed over ADB, so "oops, I ran it twice"
# must not be a way to reach that state.
say "private key"
if [ -f "$KEY" ]; then
    ok "already exists, left alone: $KEY"
else
    mkdir -p "$(dirname "$KEY")" || exit 1
    umask 077
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEY" 2>/dev/null \
        || { bad "genpkey failed"; exit 1; }
    chmod 600 "$KEY" 2>/dev/null
    ok "created $KEY"
    echo "       Back this up somewhere that is not this machine."
fi

# ── The public halves ────────────────────────────────────────────────────────
# PEM for the release script's own verify step (GATE-4); DER because
# X509EncodedKeySpec on Android wants SubjectPublicKeyInfo as raw bytes and
# res/raw hands them over without a base64 decode in the app.
say "public key"
openssl rsa -in "$KEY" -pubout -out "$PUB_PEM" 2>/dev/null \
    || { bad "could not derive $PUB_PEM"; exit 1; }
ok "$PUB_PEM"

mkdir -p "$(dirname "$PUB_DER")"
openssl rsa -in "$KEY" -pubout -outform DER -out "$PUB_DER" 2>/dev/null \
    || { bad "could not derive $PUB_DER"; exit 1; }
ok "$PUB_DER  ($(wc -c < "$PUB_DER") bytes, sha256 $(sha256sum "$PUB_DER" | cut -c1-16)…)"

# ── Prove the pair matches ───────────────────────────────────────────────────
# A mismatched pair is the one failure that would sail through every later stage
# and only show up as "signature invalid" on the car, where there is no console.
say "round trip"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf 'ota-keygen round trip' > "$tmp/probe"
openssl dgst -sha256 -sign "$KEY" -out "$tmp/probe.sig" "$tmp/probe" 2>/dev/null
if openssl dgst -sha256 -verify "$PUB_PEM" -signature "$tmp/probe.sig" "$tmp/probe" >/dev/null 2>&1; then
    ok "sign → verify with the derived public key"
else
    bad "the derived public key does not verify this key's signature"
    exit 1
fi

say "next"
cat <<'NEXT'
   1. commit  tools/ota_public_key.pem
              app-launcher/app/src/main/res/raw/ota_public_key.der
   2. the private key is NOT in the repo and must never be — .gitignore
      guards *.pem at the root, but it lives in ~/.e46m3 anyway
   3. tools/release.sh --dry-run
NEXT
