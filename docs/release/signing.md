# Release signing

Official release APKs are signed with a permanent, project-owned key. Android refuses to
upgrade an installed app when the signing certificate changes, so **losing or replacing this
key strands every installed user**. Store builds (Play, F-Droid) are out of scope until a
signed GitHub Release has been verified end to end.

## Build behavior

| Build | Signed with | Official |
| --- | --- | --- |
| `v*` tag CI | release key (required, build fails without it) | yes |
| branch / PR CI, local `assembleRelease` without key | Android debug key | no, never published |
| local `assembleRelease` with `keystore.properties` | release key | only if the fingerprint matches the pin |

`app/build.gradle.kts` reads `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` from the environment, or the same values
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) from a git-ignored
`keystore.properties`. A partial configuration fails the build rather than falling back.
`-PrequireReleaseSigning=true` fails the build when no key is configured.

## One-time setup (maintainer)

Follow the [Release keystore manual](keystore-manual.md): it covers key generation, the
certificate fingerprint, encrypted backups with a restore drill, the GitHub secrets and
variable, the first signed release, rotation and troubleshooting. In short, the repository
needs four secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) and one variable (`ANDROID_RELEASE_CERT_SHA256`).

## Verification

`scripts/verify_release_signature.py APK` runs `apksigner verify`, requires exactly one signer
and a v2/v3 signature, rejects the Android debug certificate, and compares the SHA-256
fingerprint with `ANDROID_RELEASE_CERT_SHA256` (or `--expected-sha256`). CI runs it on the tag
build, and `release.yml` runs it again on the downloaded artifact before publishing; the
fingerprint is recorded in `release-provenance.json`. Only the CI run triggered by the tag
itself may publish, because a `main` run at the same commit is debug-signed.

Local dry run with a throwaway key (do not use the real key for experiments):

```sh
keytool -genkeypair -storetype PKCS12 -keystore /tmp/test.jks -alias t -keyalg RSA -keysize 2048 \
  -validity 30 -storepass testpass -dname 'CN=test'
ANDROID_KEYSTORE_FILE=/tmp/test.jks ANDROID_KEYSTORE_PASSWORD=testpass \
ANDROID_KEY_ALIAS=t ANDROID_KEY_PASSWORD=testpass \
  ./gradlew :app:assembleRelease -PrequireReleaseSigning=true
python3 scripts/verify_release_signature.py app/build/outputs/apk/release/app-release.apk \
  --expected-sha256 <fingerprint of /tmp/test.jks>
```

## Upgrading from earlier builds

Earlier GitHub APKs (and `v0.2.0-rc.1` sideloads) are debug-signed. They cannot be upgraded in
place to a release-signed build: uninstall once, then install the signed release.

## Key rotation

Use APK Signature Scheme v3 rotation (`apksigner rotate`) only with a documented plan; do not
rotate ad hoc. Never reuse the release key for the Play upload key without deciding that
deliberately.
