#!/usr/bin/env python3
"""Mirror one published GitHub release. Never deletes releases, assets or tags."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

GITHUB_REPO = 'JasonZhang1225/BJTUselfService-KMP-Refreshed'
GITEE_REPO = 'zjg1225/BJTUselfService-KMP-Refreshed'
READY = '<!-- bjtu-mirror-ready:v1 -->'
TEST_MARKER = '<!-- bjtu-mirror-test:v1 -->'
TEST_SOURCE_TAG = 'v1.0.0-gitee-test'
TEST_TARGET_TAG = 'v2.0.0'
MAX_FILE = 100_000_000
# Leave headroom for ordinary repository attachments, which are not listed here.
RELEASE_BUDGET = 900_000_000
EXTENSIONS = {'.apk', '.ipa', '.dmg', '.msi'}


class MirrorError(Exception):
    pass


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected and urllib.parse.urlsplit(req.full_url).netloc != urllib.parse.urlsplit(newurl).netloc:
            # GitHub's authenticated asset endpoint redirects to a signed CDN URL.
            # Do not forward the repository token (or cookies) to that other host.
            redirected.remove_header('Authorization')
            redirected.remove_header('Cookie')
        return redirected


def request(url, data=None, headers=None):
    try:
        with urllib.request.build_opener(SafeRedirectHandler()).open(urllib.request.Request(url, data=data, headers=headers or {}), timeout=300) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        # Do not print request URLs/bodies: Gitee query/form parameters contain a secret.
        raise MirrorError(f'HTTP {error.code}; credentials and response body omitted') from None
    except (urllib.error.URLError, TimeoutError):
        raise MirrorError('Network request failed; details omitted') from None


class Gitee:
    def __init__(self, token):
        self.token = token
        self.base = f'https://gitee.com/api/v5/repos/{GITEE_REPO}'

    def call(self, path, method='GET', fields=None, file=None):
        fields = dict(fields or {}, access_token=self.token)
        headers = {}
        url = self.base + path
        data = None
        if method == 'GET':
            url += '?' + urllib.parse.urlencode(fields)
        elif file is None:
            data = urllib.parse.urlencode(fields).encode()
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        else:
            boundary = 'bjtu-' + os.urandom(16).hex()
            chunks = []
            for key, value in fields.items():
                chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
            chunks += [f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file.name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode(), file.read_bytes(), f'\r\n--{boundary}--\r\n'.encode()]
            data = b''.join(chunks)
            headers['Content-Type'] = f'multipart/form-data; boundary={boundary}'
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=300) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise MirrorError(f'Gitee {method} failed: HTTP {error.code}; response omitted') from None
        except (urllib.error.URLError, TimeoutError):
            raise MirrorError('Gitee request failed; details omitted') from None

    def pages(self, path):
        result = []
        for page in range(1, 1001):
            batch = self.call(path, fields={'page': page, 'per_page': 100})
            if not isinstance(batch, list):
                raise MirrorError('Unexpected Gitee list response')
            result.extend(batch)
            if len(batch) < 100:
                return result
        raise MirrorError('Pagination exceeded safety limit')


def validate_assets(assets):
    names = [a['name'] for a in assets]
    if len(assets) != 4 or {Path(n).suffix for n in names} != EXTENSIONS:
        raise MirrorError('Expected exactly four platform installers: APK, IPA, DMG, MSI')
    if any(not re.fullmatch(r'[A-Za-z0-9._-]+', n) for n in names):
        raise MirrorError('Unsafe asset filename')
    if any(not 0 < a['size'] <= MAX_FILE for a in assets):
        raise MirrorError('An installer exceeds the conservative Gitee 100 MB limit')


def check_budget(existing_bytes, missing_assets):
    if existing_bytes + sum(a['size'] for a in missing_assets) > RELEASE_BUDGET:
        raise MirrorError('Release attachment budget exceeded. Review old versions manually; nothing was deleted')


def anonymous_verify(url, expected):
    # Use a fresh urllib opener, no cookies, Authorization or Gitee access_token.
    actual = request(url)
    if len(actual) != expected.stat().st_size or hashlib.sha256(actual).digest() != hashlib.sha256(expected.read_bytes()).digest():
        raise MirrorError('Anonymous download differs from GitHub asset (or returned a login/error page)')


def sync_tag(tag, target_tag=None):
    # Push only this tag; never force/mirror or rewrite main. Existing conflicting tags fail.
    with tempfile.TemporaryDirectory() as temp:
        askpass = Path(temp) / 'askpass'
        askpass.write_text('#!/bin/sh\ncase "$1" in *Username*) printf "%s" "$GITEE_USERNAME";; *) printf "%s" "$GITEE_TOKEN";; esac\n')
        askpass.chmod(0o700)
        env = dict(os.environ, GITEE_USERNAME=os.environ.get('GITEE_USERNAME') or GITEE_REPO.split('/')[0], GIT_ASKPASS=str(askpass), GIT_TERMINAL_PROMPT='0')
        result = subprocess.run(['git', 'push', f'https://gitee.com/{GITEE_REPO}.git', f'refs/tags/{tag}:refs/tags/{target_tag or tag}'], env=env, capture_output=True)
        if result.returncode:
            raise MirrorError('Tag sync failed; check token permissions, repository quota and conflicting tags. No force push performed')


def main(promote_test=False):
    token = os.environ.get('GITEE_TOKEN', '')
    if not token:
        raise MirrorError('Configure GitHub Actions secret GITEE_TOKEN (projects scope)')
    tag = os.environ['RELEASE_TAG']
    if not re.fullmatch(r'v[A-Za-z0-9._-]+', tag) or any(x in tag.lower() for x in ('alpha', 'beta')):
        raise MirrorError('Only existing production/emergency v* releases may be mirrored')
    gh_headers = {'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + os.environ['GH_TOKEN']}
    release = json.loads(request(f'https://api.github.com/repos/{GITHUB_REPO}/releases/tags/{tag}', headers=gh_headers))
    if (release['draft'] and tag != TEST_SOURCE_TAG) or release['tag_name'] != tag:
        raise MirrorError('GitHub release is unpublished or tag mismatched')
    if promote_test and tag != TEST_SOURCE_TAG:
        raise MirrorError('Retagging is restricted to the authorized disposable Gitee test release')
    target_tag = TEST_TARGET_TAG if promote_test else tag
    assets = release['assets']
    validate_assets(assets)
    api = Gitee(token)
    releases = api.pages('/releases')
    existing = next((r for r in releases if r['tag_name'] == tag), None)
    if promote_test:
        if not existing or READY not in existing.get('body', ''):
            raise MirrorError('The original Gitee test mirror must be complete before retagging')
        if any(r['tag_name'] == target_tag and r['id'] != existing['id'] for r in releases):
            raise MirrorError('Target Gitee test tag belongs to another release; nothing overwritten')
    all_files = {r['id']: api.pages(f"/releases/{r['id']}/attach_files") for r in releases}
    files = all_files.get(existing['id'], []) if existing else []
    missing = [a for a in assets if not any(f['name'] == a['name'] for f in files)]
    check_budget(sum(f['size'] for fs in all_files.values() for f in fs), missing)
    print(f'Capacity preflight: {sum(f["size"] for fs in all_files.values() for f in fs)} existing release bytes; no automatic cleanup')
    print('Retention review (oldest first; advisory only):', ', '.join(r['tag_name'] for r in sorted(releases, key=lambda r: r.get('created_at', ''))[:-5]) or 'none')
    with tempfile.TemporaryDirectory() as temp:
        paths = []
        for asset in assets:
            path = Path(temp) / asset['name']
            if release['draft']:
                asset_url = asset.get('url', '')
                if not asset_url.startswith(f'https://api.github.com/repos/{GITHUB_REPO}/releases/assets/'):
                    raise MirrorError('Draft test asset must use its authenticated GitHub API URL')
                download_headers = dict(gh_headers, Accept='application/octet-stream')
                path.write_bytes(request(asset_url, headers=download_headers))
            else:
                path.write_bytes(request(asset['browser_download_url']))
            if path.stat().st_size != asset['size']:
                raise MirrorError('GitHub asset size mismatch')
            paths.append(path)
        sync_tag(tag, target_tag)
        fields = {'tag_name': target_tag, 'name': release['name'] or tag, 'body': (release['body'] or '').replace(READY, ''), 'prerelease': str(release['prerelease']).lower()}
        if tag == TEST_SOURCE_TAG:
            fields['body'] = fields['body'].replace(TEST_MARKER, '').rstrip() + '\n\n' + TEST_MARKER
        if promote_test:
            fields['name'] = '2.0.0 测试版 · Gitee 更新链路验证（安装包实际 1.8.6）'
        if existing:
            api.call(f"/releases/{existing['id']}", 'PATCH', fields)
        else:
            existing = api.call('/releases', 'POST', dict(fields, target_commitish=target_tag))
        rid = existing['id']
        for path in paths:
            if not any(f['name'] == path.name for f in files):
                api.call(f'/releases/{rid}/attach_files', 'POST', file=path)
            url = f'https://gitee.com/{GITEE_REPO}/releases/download/{target_tag}/{path.name}'
            anonymous_verify(url, path)
            print(f'Anonymous SHA-256 verified: {path.name}')
        # Publish completion marker last. Partial uploads never become app update candidates.
        api.call(f'/releases/{rid}', 'PATCH', dict(fields, body=fields['body'] + '\n\n' + READY))
        print(f'Mirror ready: {target_tag}')


if __name__ == '__main__':
    try:
        main(promote_test=os.environ.get('PROMOTE_GITEE_TEST', 'false') == 'true')
    except MirrorError as error:
        print(f'::error::{error}')
        raise SystemExit(1)
