#!/usr/bin/env bash
#
# Publish an OTA release, and prove the car can actually get it.
#
#   tools/release.sh --dry-run          build + every local gate, publish nothing
#   tools/release.sh                    the above, then publish and verify
#   tools/release.sh --bump patch       bump version.properties first, then all of it
#
# ## Why this is mostly gates
#
# docs/04 §10.5 records a deploy where the build, the upload and the deploy all
# reported success and the file was genuinely present on the runner — and the
# published site served 404, because upload-pages-artifact@v5 silently strips
# dot-paths. Nothing in that pipeline was lying; nothing in it was checking the
# thing that mattered either.
#
# So every stage here asserts something, and the last ones assert it about
# BYTES FETCHED BACK OFF THE PUBLIC URL rather than about bytes we produced.
#
# ## The publish is two-phase on purpose
#
# The car polls  releases/latest/download/ota-manifest.json.  A release is
# therefore created as a PRERELEASE, which that URL does not resolve to,
# verified end to end, and only then promoted. A build that fails verification
# is never once reachable at the address the car reads.
#
# ## What can and cannot be undone
#
# Publishing creates a git tag and a GitHub release. On failure this script does
# NOT delete them — it prints the command and stops. Deleting is the operator's
# call, and an automatic delete that fires on a tag which already existed for
# some other reason is a worse failure than a stray prerelease nobody serves.
set -uo pipefail

REPO="${REPO:-mushitaro/E46M3-Launcher}"
PKG="${PKG:-app.tsunagi.e46m3.launcher}"
HOME_ACTIVITY="${HOME_ACTIVITY:-app.tsunagi.e46m3.launcher.HomeActivity}"

# The `pkg/.Class` form `cmd package set-home-activity` wants, derived rather
# than repeated so the two can never disagree.
HOME_COMPONENT="$PKG/${HOME_ACTIVITY#"$PKG"}"

# The APK signing certificate, SHA-256 of the certificate DER. This is the same
# value apksigner prints, the same value assetlinks.json carries, and the same
# value PackageManager.getPackageArchiveInfo(GET_SIGNATURES) yields on the
# device — so all three are directly comparable. It is pinned here rather than
# read from the APK, because reading it from the artifact under test would make
# the check assert nothing.
SIGNER="${SIGNER:-8e529141ef09abdb50d95930a25263153834ac3b385c9f7603b15fe8bcfdb580}"

OTA_KEY="${OTA_KEY:-$HOME/.e46m3/ota-rsa2048-private.pem}"
OTA_PUB="${OTA_PUB:-tools/ota_public_key.pem}"
OTA_PUB_DER="${OTA_PUB_DER:-app-launcher/app/src/main/res/raw/ota_public_key.der}"

SDK="${SDK:-/c/Program Files (x86)/Android/android-sdk}"
BT="${BT:-35.0.0}"
AAPT2="$SDK/build-tools/$BT/aapt2.exe"
APKSIGNER="$SDK/build-tools/$BT/apksigner.bat"

OUTDIR="${OUTDIR:-/tmp/e46m3-release}"
PY="${PY:-python}"
MANIFEST_NAME="ota-manifest.json"
LATEST_URL="${LATEST_URL:-https://github.com/$REPO/releases/latest/download/$MANIFEST_NAME}"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()  { printf '   \033[32mok\033[0m  %s\n' "$*"; }
bad() { printf '   \033[31mNG\033[0m  %s\n' "$*"; }
die() { bad "$*"; exit 1; }

DRY=0
BUMP=""
NOTES=""
ALLOW_DIRTY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run)     DRY=1 ;;
        --bump)        BUMP="${2:-}"; shift ;;
        --notes-file)  NOTES="${2:-}"; shift ;;
        --allow-dirty) ALLOW_DIRTY=1 ;;
        *)             die "unknown argument: $1" ;;
    esac
    shift
done

# ── 0. Preflight ─────────────────────────────────────────────────────────────
# Everything that would otherwise fail halfway through, failing at the start
# instead — while the working tree is still untouched and nothing is published.
say "preflight"

cd "$(dirname "$0")/.." || die "cannot find the repo root"

# gradlew and apksigner.bat both shell out to java and neither reads
# gradle.properties for it, so an unset JAVA_HOME fails here with a message
# about PATH that says nothing about this project. MSYS converts the POSIX form
# on the way out to the .bat, so one value serves both.
if [ -z "${JAVA_HOME:-}" ]; then
    jh=$(sed -n 's/^org\.gradle\.java\.home=//p' app-launcher/gradle.properties | tr -d '\r')
    [ -n "$jh" ] || die "JAVA_HOME unset and gradle.properties has no org.gradle.java.home"
    JAVA_HOME=$(cygpath -u "$jh" 2>/dev/null || printf '%s' "$jh")
    export JAVA_HOME
    ok "JAVA_HOME <- gradle.properties"
fi
[ -x "$JAVA_HOME/bin/java" ] || [ -x "$JAVA_HOME/bin/java.exe" ] || die "no java under $JAVA_HOME"

for t in openssl curl sha256sum unzip git "$PY"; do
    command -v "$t" >/dev/null 2>&1 || die "missing tool: $t"
done
command -v gh >/dev/null 2>&1 || die "missing tool: gh (GitHub CLI)"
[ -f "$AAPT2" ]     || die "no aapt2 at $AAPT2"
[ -f "$APKSIGNER" ] || die "no apksigner at $APKSIGNER"
ok "tools"

[ -r "$OTA_KEY" ] || die "no OTA signing key at $OTA_KEY — run tools/ota-keygen.sh"
[ -r "$OTA_PUB" ] || die "no $OTA_PUB — run tools/ota-keygen.sh"
[ -r "$OTA_PUB_DER" ] || die "no $OTA_PUB_DER — run tools/ota-keygen.sh"
ok "OTA key + public halves"

if [ "$DRY" = "0" ]; then
    gh auth status >/dev/null 2>&1 || die "gh is not authenticated — run: gh auth login"
    ok "gh authenticated"
fi

# ── 1. Version ───────────────────────────────────────────────────────────────
say "version"

if [ -n "$BUMP" ]; then
    case "$BUMP" in patch|minor|major) ;; *) die "--bump takes patch|minor|major" ;; esac
    "$PY" - "$BUMP" <<'BUMPEOF' || die "bump failed"
import pathlib, re, sys
part = sys.argv[1]
p = pathlib.Path("app-launcher/version.properties")
s = p.read_text(encoding="utf-8")
code = int(re.search(r"^versionCode=(\d+)$", s, re.M).group(1))
name = re.search(r"^versionName=(.+)$", s, re.M).group(1).strip()
major, minor, patch = (list(map(int, name.split("."))) + [0, 0, 0])[:3]
if part == "major":   major, minor, patch = major + 1, 0, 0
elif part == "minor": minor, patch = minor + 1, 0
else:                 patch += 1
s = re.sub(r"^versionCode=\d+$", "versionCode=%d" % (code + 1), s, flags=re.M)
s = re.sub(r"^versionName=.+$", "versionName=%d.%d.%d" % (major, minor, patch), s, flags=re.M)
p.write_text(s, encoding="utf-8")
print("   %s %d -> %d.%d.%d %d" % (part, code, major, minor, patch, code + 1))
BUMPEOF
    git add app-launcher/version.properties || die "git add failed"
    git commit -q -m "release: version $(sed -n 's/^versionName=//p' app-launcher/version.properties)" \
        || die "git commit failed"
    ok "bumped and committed"
fi

VCODE=$(sed -n 's/^versionCode=//p' app-launcher/version.properties | tr -d '\r')
VNAME=$(sed -n 's/^versionName=//p' app-launcher/version.properties | tr -d '\r')
[ -n "$VCODE" ] && [ -n "$VNAME" ] || die "version.properties is missing versionCode/versionName"
case "$VCODE" in ''|*[!0-9]*) die "versionCode is not an integer: $VCODE" ;; esac
ok "versionCode=$VCODE versionName=$VNAME"

# The APK embeds BuildConfig.GIT_SHA, and a dirty tree marks it with a trailing
# "+". A published build whose source cannot be checked out again is a build
# nobody can reason about later.
if [ "$ALLOW_DIRTY" = "0" ] && [ -n "$(git status --porcelain)" ]; then
    git status --short | sed 's/^/       /'
    die "working tree is dirty — commit first, or pass --allow-dirty (marks GIT_SHA with +)"
fi

TAG="v$VNAME"
ASSET="app-launcher-$VNAME-$VCODE.apk"

# The exact commit this release is built from.
#
# `gh release create` without --target tags whatever the REMOTE default branch
# points at, which is not necessarily what was just compiled — an unpushed
# commit, or a branch other than the default one, and the published APK would
# have no tag describing its source. Pinning the SHA removes the question.
COMMIT=$(git rev-parse HEAD)
if ! git branch -r --contains "$COMMIT" 2>/dev/null | grep -q .; then
    die "HEAD ($COMMIT) is not on any remote branch — push first:
       git push"
fi
ok "commit $COMMIT is on the remote"
APK_URL="https://github.com/$REPO/releases/download/$TAG/$ASSET"

# The serial and the previous versionCode come from what the CAR currently sees,
# not from local state — that is the number this release has to beat.
#
# A network failure must NOT be read as "no releases yet": that would restart
# the serial at 1, and every device that has already seen a higher serial would
# reject this release permanently. So the two cases are distinguished by asking
# GitHub whether any release exists at all.
mkdir -p "$OUTDIR" || die "cannot create $OUTDIR"
rm -f "$OUTDIR/prev-manifest.json"
PREV_SERIAL=0
PREV_CODE=0
rel_count=$(gh release list -R "$REPO" --limit 1 2>/dev/null | wc -l | tr -d ' ')
if [ "${rel_count:-0}" -eq 0 ]; then
    ok "no published release yet — this is the first"
else
    curl -fsSL -o "$OUTDIR/prev-manifest.json" "$LATEST_URL" \
        || die "releases exist but $LATEST_URL could not be fetched — refusing to guess the serial"
    PREV_SERIAL=$("$PY" tools/otarel.py field "$OUTDIR/prev-manifest.json" serial) || die "prev manifest has no serial"
    PREV_CODE=$("$PY" tools/otarel.py field "$OUTDIR/prev-manifest.json" packages.0.versionCode) \
        || die "prev manifest has no packages.0.versionCode"
    ok "published now: serial=$PREV_SERIAL versionCode=$PREV_CODE"
fi

[ "$VCODE" -gt "$PREV_CODE" ] \
    || die "versionCode $VCODE is not greater than the published $PREV_CODE — run --bump patch"
SERIAL=$((PREV_SERIAL + 1))
ok "this release: serial=$SERIAL tag=$TAG asset=$ASSET"

# ── 2. Build ─────────────────────────────────────────────────────────────────
say "build"
( cd app-launcher && ./gradlew :app:assembleRelease --console=plain -q ) \
    || die "assembleRelease failed"
BUILT="app-launcher/app/build/outputs/apk/release/app-release.apk"
[ -f "$BUILT" ] || die "no APK at $BUILT"
ok "$BUILT ($(wc -c < "$BUILT") bytes)"

# ── 3. GATE-1 — the HOME invariant ───────────────────────────────────────────
# An APK that has lost its CATEGORY_HOME filter installs perfectly and leaves the
# car with no home screen. Recovery needs ADB, which needs the car powered and
# on the right WiFi. This gate is why the app can never ship in that state.
say "GATE-1  HOME invariant"
"$AAPT2" dump xmltree --file AndroidManifest.xml "$BUILT" \
    | "$PY" tools/otarel.py gate1 \
        --activity "$HOME_ACTIVITY" --version-code "$VCODE" --version-name "$VNAME" \
    || die "GATE-1 failed — do not publish this APK"
ok "checked on the built artifact, not on the source manifest"

# ── 4. GATE-2 — the signature ────────────────────────────────────────────────
# v1 AND v2 are both required. API 27's PackageParser understands v1 and v2 but
# not v3, so a v3-only APK would install and then return null signatures from
# getPackageArchiveInfo — which the device treats as a hard failure, correctly,
# but at the worst possible moment.
say "GATE-2  signature"
certs=$("$APKSIGNER" verify --min-sdk-version 21 --verbose --print-certs "$BUILT" 2>&1 | tr -d '\r')
printf '%s\n' "$certs" | grep -q '^Verified using v1 scheme (JAR signing): true' \
    || die "GATE-2: not v1-signed (API 27 needs it)"
printf '%s\n' "$certs" | grep -q '^Verified using v2 scheme (APK Signature Scheme v2): true' \
    || die "GATE-2: not v2-signed"
got_signer=$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr 'A-Z' 'a-z')
[ "$got_signer" = "$(printf '%s' "$SIGNER" | tr 'A-Z' 'a-z')" ] \
    || die "GATE-2: signer $got_signer != expected $SIGNER"
ok "v1+v2, signer $got_signer"

# ── 5. GATE-3 — the OTA public key actually shipped ──────────────────────────
# The device verifies the manifest against res/raw/ota_public_key.der. If that
# resource is missing, or is a stale copy from a previous key, the car rejects
# every manifest and the only way back in is ADB.
say "GATE-3  OTA public key is in the APK"
# Matched by CONTENT, not by path. AGP's release build runs
# `aapt2 optimize --shorten-resource-paths`, which rewrites
# res/raw/ota_public_key.der to something like res/j5.der before packaging.
# openRawResource() does not care — but a path-based check would have quietly
# stopped asserting anything, which is the §10.5 failure shape all over again.
# Hashing every entry also proves the key is the CURRENT one, not a stale copy.
key_sha=$(sha256sum "$OTA_PUB_DER" | cut -d' ' -f1)
key_size=$(wc -c < "$OTA_PUB_DER" | tr -d ' ')
"$PY" tools/otarel.py gate3 "$BUILT" --sha256 "$key_sha" --size "$key_size" \
    || die "GATE-3 failed — the car would reject every manifest this build fetches"
ok "matches $OTA_PUB_DER ($key_sha)"

# ── 6. Manifest ──────────────────────────────────────────────────────────────
say "manifest"
STAGE="$OUTDIR/stage"
rm -rf "$STAGE" && mkdir -p "$STAGE" || die "cannot prepare $STAGE"
cp "$BUILT" "$STAGE/$ASSET" || die "copy failed"
SIZE=$(wc -c < "$STAGE/$ASSET" | tr -d ' ')
SHA=$("$PY" tools/otarel.py sha256 "$STAGE/$ASSET") || die "sha256 failed"
ok "$ASSET  $SIZE bytes  $SHA"

notes_arg=""
[ -n "$NOTES" ] && notes_arg="--notes-file $NOTES"
# shellcheck disable=SC2086
"$PY" tools/otarel.py emit \
    --out "$STAGE/$MANIFEST_NAME" \
    --serial "$SERIAL" --package "$PKG" \
    --version-code "$VCODE" --version-name "$VNAME" \
    --url "$APK_URL" --size "$SIZE" --sha256 "$SHA" \
    --signer "$SIGNER" --min-sdk 21 $notes_arg \
    || die "could not write the manifest"

# Detached, over the exact bytes of the file. The device verifies the bytes and
# only then parses them — never the other way round — so nothing downstream may
# reformat this file.
openssl dgst -sha256 -sign "$OTA_KEY" -out "$STAGE/$MANIFEST_NAME.raw.sig" "$STAGE/$MANIFEST_NAME" \
    || die "signing the manifest failed"
base64 -w0 < "$STAGE/$MANIFEST_NAME.raw.sig" > "$STAGE/$MANIFEST_NAME.sig" \
    || die "base64 failed"
rm -f "$STAGE/$MANIFEST_NAME.raw.sig"
ok "$MANIFEST_NAME.sig ($(wc -c < "$STAGE/$MANIFEST_NAME.sig") base64 chars)"

# ── 7. GATE-4 — verify locally with the PUBLIC key ───────────────────────────
# Signing with the wrong key, or shipping a public half that does not match it,
# is the one mistake that sails through everything else and surfaces as
# "signature invalid" on a car with no console.
say "GATE-4  local verify"
base64 -d < "$STAGE/$MANIFEST_NAME.sig" > "$STAGE/verify.sig" || die "base64 -d failed"
openssl dgst -sha256 -verify "$OTA_PUB" -signature "$STAGE/verify.sig" "$STAGE/$MANIFEST_NAME" >/dev/null 2>&1 \
    || die "GATE-4: the manifest does not verify against $OTA_PUB"
rm -f "$STAGE/verify.sig"
ok "manifest verifies against the committed public key"

if [ "$DRY" = "1" ]; then
    say "dry run — stopping before publish"
    ls -la "$STAGE"
    echo
    echo "   would publish: $TAG   serial=$SERIAL   $ASSET"
    echo "   would be read by the car at: $LATEST_URL"
    exit 0
fi

# ── 8. Publish, as a PRERELEASE ──────────────────────────────────────────────
# latest/download/ does not resolve to a prerelease, so nothing the car polls
# moves until stage 10. Assets are reachable by their direct URL immediately,
# which is what stage 9 verifies.
say "publish (prerelease)"
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
    die "release $TAG already exists — bump the version, or delete it first:
       gh release delete $TAG -R $REPO --yes --cleanup-tag"
fi
notes_flag=(--generate-notes)
[ -n "$NOTES" ] && notes_flag=(--notes-file "$NOTES")
gh release create "$TAG" -R "$REPO" --prerelease --title "$TAG" \
    --target "$COMMIT" "${notes_flag[@]}" \
    "$STAGE/$ASSET" "$STAGE/$MANIFEST_NAME" "$STAGE/$MANIFEST_NAME.sig" \
    || die "gh release create failed"
ok "$TAG created as prerelease with 3 assets"

CLEANUP="gh release delete $TAG -R $REPO --yes --cleanup-tag"

# ── 9. GATE-5a — verify what the PUBLIC URL actually serves ──────────────────
# ★ This is the answer to docs/04 §10.5. Everything up to here checked bytes we
# produced. This checks bytes that came back, into a directory that starts empty
# so nothing local can be mistaken for a download.
say "GATE-5a  as received"
RECV="$OUTDIR/received"
rm -rf "$RECV" && mkdir -p "$RECV" || die "cannot prepare $RECV"
base="https://github.com/$REPO/releases/download/$TAG"

for f in "$MANIFEST_NAME" "$MANIFEST_NAME.sig"; do
    curl -fsSL -o "$RECV/$f" "$base/$f" || { bad "could not fetch $base/$f"; die "clean up with: $CLEANUP"; }
done
ok "fetched the manifest and its signature"

base64 -d < "$RECV/$MANIFEST_NAME.sig" > "$RECV/sig.bin" || { bad "bad base64"; die "clean up with: $CLEANUP"; }
openssl dgst -sha256 -verify "$OTA_PUB" -signature "$RECV/sig.bin" "$RECV/$MANIFEST_NAME" >/dev/null 2>&1 \
    || { bad "the DOWNLOADED manifest does not verify"; die "clean up with: $CLEANUP"; }
ok "downloaded manifest verifies against the committed public key"

"$PY" tools/otarel.py gate5 "$RECV/$MANIFEST_NAME" \
    --serial "$SERIAL" --package "$PKG" \
    --version-code "$VCODE" --version-name "$VNAME" \
    --url "$APK_URL" --size "$SIZE" --sha256 "$SHA" --signer "$SIGNER" \
    || die "clean up with: $CLEANUP"

recv_url=$("$PY" tools/otarel.py field "$RECV/$MANIFEST_NAME" packages.0.url) || die "clean up with: $CLEANUP"
curl -fsSL -o "$RECV/$ASSET" "$recv_url" || { bad "could not fetch $recv_url"; die "clean up with: $CLEANUP"; }
recv_sha=$("$PY" tools/otarel.py sha256 "$RECV/$ASSET")
[ "$recv_sha" = "$SHA" ] || { bad "downloaded APK sha256 $recv_sha != $SHA"; die "clean up with: $CLEANUP"; }
recv_size=$(wc -c < "$RECV/$ASSET" | tr -d ' ')
[ "$recv_size" = "$SIZE" ] || { bad "downloaded APK is $recv_size bytes, manifest says $SIZE"; die "clean up with: $CLEANUP"; }
ok "downloaded APK matches the manifest byte for byte"

recv_certs=$("$APKSIGNER" verify --min-sdk-version 21 --verbose --print-certs "$RECV/$ASSET" 2>&1 | tr -d '\r')
printf '%s\n' "$recv_certs" | grep -q '^Verified using v1 scheme (JAR signing): true' \
    || { bad "downloaded APK is not v1-signed"; die "clean up with: $CLEANUP"; }
printf '%s\n' "$recv_certs" | grep -q '^Verified using v2 scheme (APK Signature Scheme v2): true' \
    || { bad "downloaded APK is not v2-signed"; die "clean up with: $CLEANUP"; }
recv_signer=$(printf '%s\n' "$recv_certs" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr 'A-Z' 'a-z')
[ "$recv_signer" = "$(printf '%s' "$SIGNER" | tr 'A-Z' 'a-z')" ] \
    || { bad "downloaded APK signer $recv_signer != $SIGNER"; die "clean up with: $CLEANUP"; }
ok "downloaded APK: v1+v2, correct signer"

"$AAPT2" dump xmltree --file AndroidManifest.xml "$RECV/$ASSET" \
    | "$PY" tools/otarel.py gate1 \
        --activity "$HOME_ACTIVITY" --version-code "$VCODE" --version-name "$VNAME" \
    || { bad "the DOWNLOADED APK fails GATE-1"; die "clean up with: $CLEANUP"; }
ok "downloaded APK still holds the HOME invariant"

# The in-app download resumes with a Range request after a key-off. If GitHub's
# asset host ever stops honouring it, the resume silently turns into a restart,
# and the only symptom is a download that never finishes on a short key cycle.
# So it is asserted on every release rather than assumed once.
range_code=$(curl -sS -L -r 0-1023 -o "$RECV/range.bin" -w '%{http_code}' "$recv_url")
range_len=$(wc -c < "$RECV/range.bin" | tr -d ' ')
if [ "$range_code" = "206" ] && [ "$range_len" = "1024" ]; then
    ok "Range: bytes=0-1023 -> 206, 1024 bytes"
else
    bad "Range request returned $range_code with $range_len bytes (wanted 206 / 1024)"
    die "the app's resumable download would degrade to a restart — clean up with: $CLEANUP"
fi

# ── 10. Promote ──────────────────────────────────────────────────────────────
say "promote to latest"
gh release edit "$TAG" -R "$REPO" --prerelease=false --latest \
    || die "could not promote $TAG — it is verified but still a prerelease:
       gh release edit $TAG -R $REPO --prerelease=false --latest"
ok "$TAG is now the latest release"

# ── 11. GATE-5b — the URL the car polls now resolves here ────────────────────
# Stage 9 used direct asset URLs. This is the redirect chain the device actually
# follows, and it is a different code path on GitHub's side.
say "GATE-5b  latest/download resolves to this release"
rm -f "$RECV/latest-manifest.json"
curl -fsSL -o "$RECV/latest-manifest.json" "$LATEST_URL" \
    || die "$LATEST_URL did not serve anything — the car cannot see this release"
latest_serial=$("$PY" tools/otarel.py field "$RECV/latest-manifest.json" serial)
if [ "$latest_serial" = "$SERIAL" ]; then
    ok "$LATEST_URL -> serial $SERIAL"
else
    bad "$LATEST_URL still serves serial $latest_serial, expected $SERIAL"
    bad "(GitHub can lag by a few seconds — re-check before assuming it failed)"
    exit 1
fi

# ── 12. What to do next ──────────────────────────────────────────────────────
say "published"
cat <<NEXT
   $TAG   serial=$SERIAL   versionCode=$VCODE   $ASSET
   commit $COMMIT

   The car will pick this up on its next check. To push it now over ADB
   instead — still the bootstrap channel, and always the escape hatch:

       tools/deploy.sh

   If anything goes wrong and the unit stops being HOME:

       adb shell cmd package set-home-activity $HOME_COMPONENT

   To withdraw this release, publish a HIGHER serial that omits it. Never
   lower a serial: devices remember the highest they have seen.
NEXT
