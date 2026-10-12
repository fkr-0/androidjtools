import unittest

import check_manifest_hardening as chk

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def manifest(application_attrs: str) -> str:
    return f'<manifest {NS}><application {application_attrs}/></manifest>'


GOOD = 'android:allowBackup="false" android:networkSecurityConfig="@xml/network_security_config"'


class ManifestHardeningTest(unittest.TestCase):
    def test_hardened_manifest_has_no_problems(self):
        self.assertEqual([], chk.check(manifest(GOOD)))

    def test_backup_enabled_is_a_problem(self):
        problems = chk.check(manifest(GOOD.replace('"false"', '"true"')))
        self.assertTrue(any("allowBackup" in p for p in problems))

    def test_missing_allow_backup_defaults_to_enabled_and_is_a_problem(self):
        problems = chk.check(manifest('android:networkSecurityConfig="@xml/n"'))
        self.assertTrue(any("allowBackup" in p for p in problems))

    def test_missing_network_security_config_is_a_problem(self):
        problems = chk.check(manifest('android:allowBackup="false"'))
        self.assertTrue(any("networkSecurityConfig" in p for p in problems))

    def test_cleartext_traffic_enabled_is_a_problem(self):
        problems = chk.check(manifest(GOOD + ' android:usesCleartextTraffic="true"'))
        self.assertTrue(any("usesCleartextTraffic" in p for p in problems))

    def test_manifest_without_application_is_a_problem(self):
        self.assertTrue(chk.check(f"<manifest {NS}/>"))


if __name__ == "__main__":
    unittest.main()
