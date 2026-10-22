import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('mirror', Path(__file__).parents[1] / 'mirror-gitee-release.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class MirrorTest(unittest.TestCase):
    def assets(self):
        return [dict(name='app' + ext, size=42) for ext in ['.apk', '.ipa', '.dmg', '.msi']]

    def test_four_platforms_required_before_mutation(self):
        m.validate_assets(self.assets())
        for assets in [self.assets()[:3], self.assets() + [dict(name='extra.apk', size=1)]]:
            with self.assertRaises(m.MirrorError):
                m.validate_assets(assets)

    def test_size_and_path_limits(self):
        for name, size in [('../app.apk', 1), ('app.apk', m.MAX_FILE + 1), ('app.apk', 0)]:
            assets = self.assets()
            assets[0] = dict(name=name, size=size)
            with self.assertRaises(m.MirrorError):
                m.validate_assets(assets)

    def test_quota_does_not_delete(self):
        m.check_budget(10, self.assets())
        with self.assertRaises(m.MirrorError):
            m.check_budget(m.RELEASE_BUDGET, self.assets())

    def test_anonymous_download_checks_content_not_just_status(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'app.apk'
            path.write_bytes(b'installer')
            with patch.object(m, 'request', return_value=b'installer'):
                m.anonymous_verify('https://gitee.com/download', path)
            for body in [b'<html>login</html>', b'corrupted']:
                with patch.object(m, 'request', return_value=body), self.assertRaises(m.MirrorError):
                    m.anonymous_verify('https://gitee.com/download', path)


if __name__ == '__main__':
    unittest.main()
