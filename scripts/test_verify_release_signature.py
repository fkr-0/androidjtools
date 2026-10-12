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


class EvaluateTest(unittest.TestCase):
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
