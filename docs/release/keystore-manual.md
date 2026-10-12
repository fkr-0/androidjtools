# Release keystore manual

Step-by-step procedure for creating, storing, wiring, testing, rotating and (if needed)
recovering the Android DJ Tools release signing key. For how builds use the key, see
[Release signing](signing.md).

Commands marked **you run this** publish to GitHub or handle secrets. They are meant to be
typed by the maintainer, never pasted into a chat, issue, PR or log.

## 0. Rules that cannot be relaxed

1. **The key is the app's identity.** Android refuses to update an installed app signed with a
   different certificate. Lose the key or its password and every installed copy is stranded.
2. **Never commit it.** `*.jks`, `*.keystore`, `*.p12` and `keystore.properties` are
   git-ignored; do not override that with `git add -f`.
3. **Never reuse the debug key** for releases. CI and `verify_release_signature.py` reject the
   `Android Debug` certificate on tag builds.
4. **Keep at least two independent, encrypted backups** before the first public release.
5. **Do not experiment with the real key.** Use a throwaway key for dry runs (section 7).

## 1. Prerequisites

| Need | Check |
| --- | --- |
| JDK 17 `keytool` | `keytool -help` |
| Android SDK build-tools `apksigner` | `apksigner --version` (or `$ANDROID_HOME/build-tools/*/apksigner`) |
| GitHub CLI, logged in with repo admin | `gh auth status` |
| A password manager or an `age`/`gpg` setup for backups | |
| Repository checkout on a branch containing the signing support | `ls scripts/verify_release_signature.py` |

## 2. Generate the key (you run this)

Use a private directory outside the repository.

```sh
install -d -m 700 ~/.local/share/androidjtools-signing
cd ~/.local/share/androidjtools-signing

keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore release.p12 \
  -alias androidjtools \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=Android DJ Tools, O=<your name or org>, C=<country code>"

chmod 600 release.p12
```

Notes:

- `keytool` prompts for the keystore password. Choose a long random password (a password
  manager's generator). Do not type it on the command line (`-storepass`) because it ends up in
  shell history and the process list.
- **PKCS12 uses one password for the store and the key.** Use the same value for
  `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD`. If `keytool` offers a separate key
  password, press Enter to reuse the store password.
- `-validity 10000` is about 27 years, comfortably beyond the Play Store's minimum validity
  (October 2033) should store distribution come later.
- The `-dname` is public: it is shown in the certificate. Do not put anything private in it.
- The file extension is cosmetic. Use `release.p12` or `release.jks`; the build reads the format
  from the file, and both are git-ignored.

## 3. Record the certificate fingerprint

The SHA-256 fingerprint is public. CI pins it so a substituted key cannot publish a release.

```sh
keytool -list -v -keystore release.p12 -alias androidjtools | grep -E 'Owner|SHA256|Valid'
```

Copy the `SHA256:` value (colon-separated hex). `verify_release_signature.py` accepts colons
and upper or lower case. Save it somewhere durable, such as `docs/release/signing.md` after the
first release or your password manager.

Check the key is what you intended before going further:

```sh
keytool -list -v -keystore release.p12 | grep -E 'Key algorithm|Key size|Signature algorithm'
```

Expected: RSA, 4096 bits.

## 4. Back it up (do this before step 5)

Create an **encrypted** copy and store it in two physically or logically separate places (for
example a password-manager attachment and an offline encrypted USB stick).

With `age` (preferred):

```sh
age -p -o release.p12.age release.p12     # prompts for a backup passphrase
```

With `gpg`:

```sh
gpg --symmetric --cipher-algo AES256 --output release.p12.gpg release.p12
```

Also store, next to each backup: the keystore password, the alias (`androidjtools`), the
certificate fingerprint, and the generation date. Without the password the file is useless.

**Prove the backup works** by decrypting it to a scratch location and listing it:

```sh
age -d release.p12.age > /tmp/restore-test.p12      # or: gpg --decrypt release.p12.gpg
keytool -list -keystore /tmp/restore-test.p12 -alias androidjtools
shred -u /tmp/restore-test.p12
```

The fingerprint printed must equal the one from step 3. Repeat this drill once a year and
after any storage change.

## 5. Configure GitHub (you run this)

From a checkout of the repository (or add `--repo OWNER/REPO`):

```sh
cd ~/.local/share/androidjtools-signing

# keystore as one base64 line (GNU coreutils; on macOS use: base64 -i release.p12)
base64 -w0 release.p12 | gh secret set ANDROID_KEYSTORE_BASE64

gh secret set ANDROID_KEYSTORE_PASSWORD     # prompts; paste the keystore password
gh secret set ANDROID_KEY_PASSWORD          # same value as the keystore password for PKCS12
gh secret set ANDROID_KEY_ALIAS --body androidjtools

# public, so a variable rather than a secret: the pinned certificate fingerprint
gh variable set ANDROID_RELEASE_CERT_SHA256 --body '<SHA256 fingerprint from step 3>'
```

Verify the names exist without revealing values:

```sh
gh secret list
gh variable list
```

Expected: four secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`) and one variable
(`ANDROID_RELEASE_CERT_SHA256`). GitHub never shows secret values again, which is why the
backup in step 4 matters.

Optional hardening: create a GitHub **environment** named `release` with required reviewers,
move the secrets there, and add `environment: release` to the signing jobs. The current
workflows read repository-level secrets and do not use an environment.

Secrets are not available to workflows triggered from forks, so PR builds from forks stay
debug-signed and unofficial by design.

## 6. Local release build with the real key (optional)

Prefer CI for official builds. If a local signed build is required:

```sh
umask 077
cat > keystore.properties <<'EOF'
storeFile=/home/<you>/.local/share/androidjtools-signing/release.p12
storePassword=<keystore password>
keyAlias=androidjtools
keyPassword=<same password for PKCS12>
EOF

./gradlew :app:assembleRelease -PrequireReleaseSigning=true
python3 scripts/verify_release_signature.py app/build/outputs/apk/release/app-release.apk \
  --expected-sha256 '<fingerprint from step 3>'
```

`keystore.properties` is git-ignored. Delete it when finished if the machine is shared.

## 7. Dry run with a throwaway key

Use this to test build or workflow changes without risking the real key.

```sh
keytool -genkeypair -storetype PKCS12 -keystore /tmp/test.p12 -alias t \
  -keyalg RSA -keysize 2048 -validity 30 -storepass testpass -dname 'CN=Throwaway Test'

ANDROID_KEYSTORE_FILE=/tmp/test.p12 ANDROID_KEYSTORE_PASSWORD=testpass \
ANDROID_KEY_ALIAS=t ANDROID_KEY_PASSWORD=testpass \
  ./gradlew :app:assembleRelease -PrequireReleaseSigning=true

python3 scripts/verify_release_signature.py app/build/outputs/apk/release/app-release.apk \
  --expected-sha256 "$(keytool -list -v -keystore /tmp/test.p12 -storepass testpass | sed -n 's/.*SHA256: //p')"
rm -f /tmp/test.p12
```

Expected failures, which prove the guards work:

| Setup | Result |
| --- | --- |
| no key, `-PrequireReleaseSigning=true` | `Release signing is required but missing: ...` |
| only some variables set | `Incomplete release signing configuration; missing: ...` |
| nonexistent keystore path | `Release keystore not found: ...` |
| verifier with a wrong `--expected-sha256` | `certificate fingerprint ... does not match pinned ...` |
| verifier on a debug-signed APK | `APK is signed with the shared Android debug certificate` |

## 8. First signed release, end to end

1. Merge the signing branch to `main` and confirm CI is green (debug-signed, unofficial).
2. Check `versionName`/`versionCode` in `app/build.gradle.kts` and that `CHANGELOG.md` has a
   heading `## <versionName> - <date>`. CI and the release workflow both require it.
3. Create and push the tag, which must equal `v<versionName>`:
   ```sh
   git tag -s v0.2.0 -m "Android DJ Tools 0.2.0"   # or: git tag -a ...
   git push origin v0.2.0
   ```
4. Watch **Android CI** on the tag. It must restore the keystore, build with
   `-PrequireReleaseSigning=true`, and pass the signature check against the pinned fingerprint.
5. **Publish GitHub Release** runs only from the tag-triggered CI run, re-verifies the APK, and
   attaches the APK, `SHA256SUMS`, and `release-provenance.json` (which records the certificate
   fingerprint and `signing_profile: release`).
6. Independently verify what users will download:
   ```sh
   gh release download v0.2.0 --pattern '*.apk' --pattern 'SHA256SUMS' --pattern 'release-provenance.json'
   sha256sum -c SHA256SUMS
   python3 scripts/verify_release_signature.py androidjtools-v0.2.0.apk
   ```
   Run the last command with `ANDROID_RELEASE_CERT_SHA256` set to the value from step 3.
7. Install on a real device and run a smoke test. Then install the **next** release over it
   to prove that updates work (same signer).

If the tag build fails before the release job, nothing is published. Fix forward on `main`,
delete the failed tag (`git push --delete origin v0.2.0 && git tag -d v0.2.0`), and re-tag.
The release job refuses to overwrite an existing GitHub Release.

## 9. Upgrading from debug-signed builds

Earlier GitHub APKs are debug-signed. They cannot be updated in place to a release-signed
build. Users must uninstall once (their on-device data is removed; Sample Lab remains the
canonical library) and install the signed release. Say so in the release notes.

## 10. Rotation and compromise

**Routine rotation is not needed**: the key is valid for about 27 years.

**If the key is lost** (no usable backup): you cannot sign updates for the existing app ID.
Users must uninstall and reinstall a build signed by a new key. Generate a new key with section 2,
update the secrets and the pinned fingerprint, and publish a clear migration notice.

**If the key or its password may be exposed:**

1. Stop publishing from the old key. Remove the GitHub secrets
   (`gh secret delete ANDROID_KEYSTORE_BASE64`, and the others).
2. Generate a new key (section 2) and back it up (section 4).
3. Because `minSdk` is 28, you can keep updates working with **APK Signature Scheme v3 key
   rotation**: create a lineage with the old key as the proof of rotation and sign with the new one.
   ```sh
   apksigner rotate --out lineage.bin \
     --old-signer --ks old-release.p12 --ks-key-alias androidjtools \
     --new-signer --ks new-release.p12 --ks-key-alias androidjtools
   apksigner sign --ks new-release.p12 --ks-key-alias androidjtools \
     --lineage lineage.bin --out app-rotated.apk app-unsigned-or-release.apk
   ```
   This requires the **old key to still be in your possession**, so it is only useful for
   exposure, not for loss. It would also require extending the Gradle and CI configuration, so
   treat it as a planned change with its own review, not an emergency edit. Note that an
   attacker holding the old key can still sign for users who have not updated.
4. Update `ANDROID_RELEASE_CERT_SHA256` and publish the new fingerprint on the release page.

## 11. Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| `keytool error: Keystore was tampered with, or password was incorrect` | Wrong password, or a corrupted base64 secret. Re-run step 5's `base64 -w0 ... | gh secret set`. |
| CI: `Release signing is required but missing: ...` | A secret is unset or empty, or the workflow runs from a fork. Check `gh secret list`. |
| CI: `Release keystore not found` | The restore step did not run; the build was not triggered by a tag. |
| CI: `an expected release certificate fingerprint is required` | `ANDROID_RELEASE_CERT_SHA256` variable is missing. Set it with `gh variable set`. |
| CI: `certificate fingerprint ... does not match pinned ...` | The secret key differs from the one that was pinned. Do not "fix" by editing the pin without understanding why. |
| CI: `APK is signed with the shared Android debug certificate` | The tag build fell back to debug signing; check the Gradle step passed `-PrequireReleaseSigning=true`. |
| Release workflow: `not a release-signed run` | The triggering CI run was for `main`, not the tag; expected. Wait for the tag run. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on device | The installed app has a different signer (for example a debug build). Uninstall it first. |
| `apksigner: command not found` | Add `$ANDROID_HOME/build-tools/<version>` to `PATH`, or set `ANDROID_HOME`. |
| `base64: invalid option -- 'w'` | macOS: use `base64 -i release.p12 | tr -d '\n'`. |

## 12. Checklist

- [ ] Key generated with RSA 4096, PKCS12, outside the repository, mode 600
- [ ] Fingerprint recorded
- [ ] Two encrypted backups stored, restore drill passed
- [ ] Four secrets and one variable set; `gh secret list` / `gh variable list` match
- [ ] Throwaway-key dry run passes locally
- [ ] Tag build is green and the published APK verifies against the pin
- [ ] An update installed over the first signed release succeeds on a device
- [ ] No store registration until all of the above are done
