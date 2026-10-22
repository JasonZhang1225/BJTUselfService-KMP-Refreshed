import importlib.util
import contextlib
import io
import json
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

    def run_mirror(self, existing=False, fail_download=False, oversized=False, draft=False, tag="v9.0.0-KMP", promote=False, ready=True, already_promoted=False):
        assets = [dict(a, browser_download_url='https://github.com/' + a['name'], url=f'https://api.github.com/repos/{m.GITHUB_REPO}/releases/assets/{index}') for index, a in enumerate(self.assets())]
        if oversized:
            assets[0]['size'] = m.MAX_FILE + 1
        release = dict(tag_name=tag, name='Release', body='Notes', prerelease=True, draft=draft, assets=assets)
        calls = []
        class Api:
            def pages(self, path):
                if path == '/releases':
                    return [dict(id=1, tag_name=m.TEST_TARGET_TAG if already_promoted else tag, created_at='2026-10-22', body=(m.READY if ready else '') + (m.TEST_MARKER + m.TEST_SOURCE_TAG if already_promoted else ''))] if existing else []
                return assets if existing else []
            def call(self, path, method='GET', fields=None, file=None):
                calls.append((method, path, fields))
                return {'id': 1}
        def download(url, **kwargs):
            if '/releases/tags/' in url:
                return json.dumps(release).encode()
            if fail_download and 'gitee.com' in url and url.endswith('.msi'):
                return b'<html>login</html>'
            return b'x' * 42
        with patch.dict(m.os.environ, {'GITEE_TOKEN': 'dummy', 'GH_TOKEN': 'dummy', 'RELEASE_TAG': tag}), \
                patch.object(m, 'Gitee', return_value=Api()), patch.object(m, 'request', side_effect=download), \
                patch.object(m, 'sync_tag') as push, contextlib.redirect_stdout(io.StringIO()):
            error = None
            try:
                m.main(promote_test=promote)
            except m.MirrorError as caught:
                error = caught
        return calls, push.call_count, error

    def test_marker_is_last_after_four_verified_uploads(self):
        calls, pushed, error = self.run_mirror()
        self.assertIsNone(error)
        self.assertEqual(1, pushed)
        self.assertEqual(4, sum(method == 'POST' and path.endswith('/attach_files') for method, path, _ in calls))
        self.assertTrue(calls[-1][2]['body'].endswith(m.READY))
        self.assertTrue(all(m.READY not in str(fields) for _, _, fields in calls[:-1]))

    def test_partial_failure_never_marks_complete(self):
        calls, _, error = self.run_mirror(fail_download=True)
        self.assertIsInstance(error, m.MirrorError)
        self.assertTrue(all(m.READY not in str(fields) for _, _, fields in calls))

    def test_retry_verifies_existing_files_without_upload_or_delete(self):
        calls, _, error = self.run_mirror(existing=True)
        self.assertIsNone(error)
        self.assertTrue(all(method == 'PATCH' for method, _, _ in calls))
        self.assertTrue(calls[-1][2]['body'].endswith(m.READY))

    def test_oversized_assets_fail_before_tag_or_release_mutation(self):
        calls, pushed, error = self.run_mirror(oversized=True)
        self.assertIsInstance(error, m.MirrorError)
        self.assertEqual([], calls)
        self.assertEqual(0, pushed)

    def test_regular_drafts_cannot_be_mirrored(self):
        calls, pushed, error = self.run_mirror(draft=True)
        self.assertIsInstance(error, m.MirrorError)
        self.assertEqual([], calls)
        self.assertEqual(0, pushed)

    def test_authorized_test_draft_downloads_through_authenticated_asset_api(self):
        calls, _, error = self.run_mirror(draft=True, tag=m.TEST_SOURCE_TAG)
        self.assertIsNone(error)
        self.assertTrue(calls[-1][2]['body'].endswith(m.READY))
        self.assertIn(m.TEST_MARKER, calls[-1][2]['body'])

    def test_test_promotion_requires_original_complete_mirror(self):
        for existing, ready in [(False, False), (True, False)]:
            calls, pushed, error = self.run_mirror(tag=m.TEST_SOURCE_TAG, draft=True, existing=existing, ready=ready, promote=True)
            self.assertIsInstance(error, m.MirrorError)
            self.assertEqual([], calls)
            self.assertEqual(0, pushed)

    def test_test_promotion_keeps_installers_and_updates_only_same_release(self):
        calls, _, error = self.run_mirror(tag=m.TEST_SOURCE_TAG, draft=True, existing=True, promote=True)
        self.assertIsNone(error)
        self.assertTrue(all(method == 'PATCH' for method, _, _ in calls))
        self.assertEqual(m.TEST_TARGET_TAG, calls[-1][2]['tag_name'])
        self.assertIn(m.TEST_MARKER, calls[-1][2]['body'])

    def test_promotion_retry_after_patch_succeeded_resumes_hash_verification(self):
        calls, _, error = self.run_mirror(tag=m.TEST_SOURCE_TAG, draft=True, existing=True, promote=True, ready=False, already_promoted=True)
        self.assertIsNone(error)
        self.assertEqual(m.TEST_TARGET_TAG, calls[-1][2]['tag_name'])
        self.assertTrue(calls[-1][2]['body'].endswith(m.READY))

    def test_arbitrary_releases_cannot_use_test_promotion(self):
        calls, pushed, error = self.run_mirror(existing=True, promote=True)
        self.assertIsInstance(error, m.MirrorError)
        self.assertEqual([], calls)
        self.assertEqual(0, pushed)

    def test_cdn_redirect_never_forwards_repository_token(self):
        original = m.urllib.request.Request('https://api.github.com/asset', headers={'Authorization': 'Bearer dummy', 'Cookie': 'dummy'})
        handler = m.SafeRedirectHandler()
        redirected = handler.redirect_request(original, None, 302, '', {}, 'https://release-assets.githubusercontent.com/file')
        self.assertIsNone(redirected.get_header('Authorization'))
        self.assertIsNone(redirected.get_header('Cookie'))
        same_host = handler.redirect_request(original, None, 302, '', {}, 'https://api.github.com/other')
        self.assertEqual('Bearer dummy', same_host.get_header('Authorization'))


if __name__ == '__main__':
    unittest.main()
