#!/usr/bin/env python3
"""Rebuild the original OpenJDK 25 jpackage launcher with macOS SDK 27+."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
from concurrent.futures import ThreadPoolExecutor
from urllib.request import urlopen


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', default=os.environ.get('DESKTOP_PACKAGE_JAVA_HOME') or os.environ.get('JAVA_HOME'))
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        parser.error('This launcher build supports Apple Silicon macOS only.')
    if not args.java_home:
        parser.error('Provide --java-home pointing to JDK 25 (or DESKTOP_PACKAGE_JAVA_HOME).')
    java = Path(args.java_home).expanduser().resolve()
    release = (java / 'release').read_text()
    if not any(line.startswith('JAVA_VERSION="25.') or line == 'JAVA_VERSION="25"' for line in release.splitlines()):
        parser.error('Use JDK 25, matching the pinned OpenJDK 25 launcher sources.')
    sdk_version = run('xcrun', '--sdk', 'macosx', '--show-sdk-version')
    if int(sdk_version.split('.')[0]) < 27:
        parser.error('macOS SDK 27+ is required; select a suitable Xcode with DEVELOPER_DIR.')
    sdk = run('xcrun', '--sdk', 'macosx', '--show-sdk-path')
    manifest = json.loads(Path(__file__).with_name('macos-launcher-sources.json').read_text())
    output = args.output_dir.expanduser().resolve()
    source_root = output / 'openjdk-src'
    source_root.mkdir(parents=True, exist_ok=True)

    def fetch(item):
        relative, expected = item
        local = source_root / relative
        local.parent.mkdir(parents=True, exist_ok=True)
        if local.is_file():
            data = local.read_bytes()
        else:
            url = f"https://raw.githubusercontent.com/openjdk/jdk/{manifest['ref']}/{relative}"
            with urlopen(url, timeout=30) as response:
                data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f'OpenJDK source checksum mismatch: {relative}')
        local.write_bytes(data)
        return local

    with ThreadPoolExecutor(max_workers=6) as pool:
        files = list(pool.map(fetch, sorted(manifest['files'].items())))
    includes = sorted({str(path.parent) for path in files if path.suffix == '.h'})
    flags = ['-target', 'arm64-apple-macos12.0', '-isysroot', sdk, '-O2',
             '-I' + str(java / 'include'), '-I' + str(java / 'include/darwin')]
    flags += ['-I' + directory for directory in includes]
    objects = []
    for source in files:
        if source.suffix not in ('.cpp', '.c'):
            continue
        obj = source.with_suffix('.o')
        compiler = 'clang++' if source.suffix == '.cpp' else 'clang'
        options = ['-std=c++17'] if source.suffix == '.cpp' else []
        subprocess.run(['xcrun', compiler, *options, *flags, '-c', str(source), '-o', str(obj)], check=True)
        objects.append(str(obj))
    launcher = output / 'jpackageapplauncher'
    subprocess.run(['xcrun', 'clang++', '-target', 'arm64-apple-macos12.0', '-isysroot', sdk,
                    *objects, '-framework', 'Cocoa', '-Wl,-rpath,@executable_path/../Frameworks/',
                    '-Wl,-rpath,@executable_path/../PlugIns/', '-o', str(launcher)], check=True)
    build = run('xcrun', 'vtool', '-show-build', str(launcher))
    if 'minos 12.0' not in build or f'sdk {sdk_version}' not in build:
        raise RuntimeError(f'Unexpected launcher deployment target or SDK:\n{build}')
    metadata = {'openjdk_ref': manifest['ref'], 'openjdk_tag': manifest['tag'],
                'sdk': sdk_version, 'minimum_macos': '12.0', 'architecture': 'arm64',
                'sha256': hashlib.sha256(launcher.read_bytes()).hexdigest()}
    (output / 'build-info.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(build)
    print(f'Launcher: {launcher}')
    print(f'OpenJDK sources and license notices: {source_root}')


if __name__ == '__main__':
    main()
