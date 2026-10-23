import unittest

import verify_release_signature as vrs

FP = "a1" * 32
OTHER_FP = "b2" * 32


def apksigner_output(dn: str = "CN=Android DJ Tools Release, O=Example", digest: str = FP, v2: bool = True, v3: bool = False, signers: int = 1) -> str:
    lines = [
        "Verifies",
        "Verified using v1 scheme (JAR signing): false",
        f"Verified using v2 scheme (APK Signature Scheme v2): {str(v2).lower()}",
        f"Verified using v3 scheme (APK Signature Scheme v3): {str(v3).lower()}",
        "Verified using v4 scheme (APK Signature Scheme v4): false",
        f"Number of signers: {signers}",
    ]
    for index in range(1, signers + 1):
        lines += [
            f"Signer #{index} certificate DN: {dn}",
            f"Signer #{index} certificate SHA-256 digest: {digest}",
            f"Signer #{index} certificate SHA-1 digest: {'c3' * 20}",
        ]
    return "\n".join(lines) + "\n"


def ranged_output(dn: str, digest: str) -> str:
    """Newer apksigner builds label v3 signers with their SDK range instead of a number."""
    return "\n".join(
        [
            "Verifies",
            "Verified using v2 scheme (APK Signature Scheme v2): true",
            "Verified using v3 scheme (APK Signature Scheme v3): true",
            "Number of signers: 1",
            f"Signer (minSdkVersion=28, maxSdkVersion=2147483647) certificate DN: {dn}",
            f"Signer (minSdkVersion=28, maxSdkVersion=2147483647) certificate SHA-256 digest: {digest}",
        ]
    ) + "\n"


def per_scheme_output(dn: str, digest: str, schemes=("V2",), number_of_signers: int = 1) -> str:
    """Current apksigner (hosted runners): one `Vn Signer:` block per verified scheme, no signer number."""
    lines = [
        "Verifies",
        "Verified using v1 scheme (JAR signing): false",
        f"Verified using v2 scheme (APK Signature Scheme v2): {str('V2' in schemes).lower()}",
        f"Verified using v3 scheme (APK Signature Scheme v3): {str('V3' in schemes).lower()}",
        "Verified using v3.1 scheme (APK Signature Scheme v3.1): false",
        f"Number of signers: {number_of_signers}",
    ]
    for scheme in schemes:
        lines += [
            f"{scheme} Signer: certificate DN: {dn}",
            f"{scheme} Signer: certificate SHA-256 digest: {digest}",
            f"{scheme} Signer: certificate SHA-1 digest: {'c3' * 20}",
        ]
    return "\n".join(lines) + "\n"


class EvaluateTest(unittest.TestCase):
    def test_a_branch_build_with_a_configured_pin_still_passes_when_debug_is_allowed(self):
        """Once ANDROID_RELEASE_CERT_SHA256 is set repository-wide, branch builds (debug-signed) must not trip on it."""
        result = vrs.evaluate(
            apksigner_output(dn="C=US, O=Android, CN=Android Debug", digest=OTHER_FP),
            expected_sha256=FP,
            allow_debug=True,
        )
        self.assertTrue(result.debug)

    def test_a_non_debug_certificate_is_still_pinned_when_debug_is_allowed(self):
        with self.assertRaisesRegex(vrs.SignatureError, "does not match"):
            vrs.evaluate(apksigner_output(digest=OTHER_FP), expected_sha256=FP, allow_debug=True)

    def test_per_scheme_signer_blocks_are_one_signer(self):
        result = vrs.evaluate(per_scheme_output("CN=Android DJ Tools", FP), expected_sha256=FP)
        self.assertEqual(result.sha256, FP)

    def test_the_same_certificate_listed_for_v2_and_v3_is_still_one_signer(self):
        result = vrs.evaluate(per_scheme_output("CN=Android DJ Tools", FP, schemes=("V2", "V3")), expected_sha256=FP)
        self.assertEqual(result.sha256, FP)

    def test_two_different_certificates_are_rejected(self):
        text = per_scheme_output("CN=A", FP) + per_scheme_output("CN=B", OTHER_FP).split("\n", 6)[6]
        with self.assertRaisesRegex(vrs.SignatureError, "exactly one signer"):
            vrs.evaluate(text, expected_sha256=FP)

    def test_a_reported_signer_count_above_one_is_rejected(self):
        with self.assertRaisesRegex(vrs.SignatureError, "exactly one signer"):
            vrs.evaluate(per_scheme_output("CN=A", FP, number_of_signers=2), expected_sha256=FP)

    def test_signer_labelled_with_an_sdk_range_is_still_one_signer(self):
        result = vrs.evaluate(ranged_output("CN=Android DJ Tools", FP), expected_sha256=FP)
        self.assertEqual(result.sha256, FP)

    def test_no_signer_error_shows_what_apksigner_printed(self):
        with self.assertRaisesRegex(vrs.SignatureError, "something unexpected"):
            vrs.evaluate("Verifies\nsomething unexpected\n", expected_sha256=FP)

    def test_matching_release_certificate_passes(self):
        result = vrs.evaluate(apksigner_output(), expected_sha256=FP)
        self.assertEqual(result.sha256, FP)

    def test_expected_fingerprint_accepts_colons_and_uppercase(self):
        colon = ":".join(FP[i : i + 2] for i in range(0, len(FP), 2)).upper()
        self.assertEqual(vrs.evaluate(apksigner_output(), expected_sha256=colon).sha256, FP)

    def test_fingerprint_mismatch_fails(self):
        with self.assertRaisesRegex(vrs.SignatureError, "does not match"):
            vrs.evaluate(apksigner_output(), expected_sha256=OTHER_FP)

    def test_android_debug_certificate_is_rejected(self):
        with self.assertRaisesRegex(vrs.SignatureError, "debug"):
            vrs.evaluate(apksigner_output(dn="C=US, O=Android, CN=Android Debug"), expected_sha256=FP)

    def test_debug_certificate_allowed_only_when_explicit(self):
        result = vrs.evaluate(
            apksigner_output(dn="C=US, O=Android, CN=Android Debug"),
            expected_sha256=None,
            allow_debug=True,
        )
        self.assertTrue(result.debug)

    def test_missing_expected_fingerprint_is_an_error_unless_debug_allowed(self):
        with self.assertRaisesRegex(vrs.SignatureError, "expected"):
            vrs.evaluate(apksigner_output(), expected_sha256=None)

    def test_multiple_signers_rejected(self):
        with self.assertRaisesRegex(vrs.SignatureError, "exactly one signer"):
            vrs.evaluate(apksigner_output(signers=2), expected_sha256=FP)

    def test_unverified_modern_scheme_rejected(self):
        with self.assertRaisesRegex(vrs.SignatureError, "v2 or v3"):
            vrs.evaluate(apksigner_output(v2=False, v3=False), expected_sha256=FP)

    def test_v3_only_is_accepted(self):
        self.assertEqual(vrs.evaluate(apksigner_output(v2=False, v3=True), expected_sha256=FP).sha256, FP)

    def test_unparseable_output_rejected(self):
        with self.assertRaisesRegex(vrs.SignatureError, "exactly one signer"):
            vrs.evaluate("DOES NOT VERIFY\n", expected_sha256=FP)

    def test_malformed_expected_fingerprint_rejected(self):
        with self.assertRaisesRegex(vrs.SignatureError, "64 hex"):
            vrs.evaluate(apksigner_output(), expected_sha256="abc123")


if __name__ == "__main__":
    unittest.main()
