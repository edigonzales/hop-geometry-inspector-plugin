#!/usr/bin/env python3
"""Install canonical ZIPs in a disposable Hop home and verify captured documentation examples."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hop-home', required=True, type=Path)
    parser.add_argument('--plugin-zip', required=True, type=Path)
    parser.add_argument('--geometry-zip', required=True, type=Path)
    args = parser.parse_args()
    home = args.hop_home.resolve(strict=True)
    for name in ('hop-geometry-inspector', 'hop-geometry-type'):
        if (home / 'plugins/misc' / name).exists():
            raise SystemExit('Use a clean disposable Hop installation: ' + str(home))
    for bundle in (args.geometry_zip, args.plugin_zip):
        with zipfile.ZipFile(bundle) as archive:
            for item in archive.infolist():
                target = (home / item.filename).resolve()
                if home not in target.parents:
                    raise ValueError('Invalid ZIP path: ' + item.filename)
            archive.extractall(home)
    command = ['mvn', '-U', '-B', '-ntp']
    if os.environ.get('MAVEN_SETTINGS'):
        command += ['-s', os.environ['MAVEN_SETTINGS']]
    command += ['-pl', 'integration-tests', 'test',
                '-Dgeometry.inspector.plugins.dir=' + str(home / 'plugins'),
                '-Dgeometry.inspector.examples.dir=' + str(ROOT / 'examples')]
    # Only load transforms used by the examples. Unrelated VFS plugins require the
    # full application's library classpath, which this Maven harness intentionally lacks.
    with tempfile.TemporaryDirectory(prefix='inspector-installed-') as temporary:
        plugins = Path(temporary) / 'plugins'
        for name in ('datagrid', 'filterrows', 'selectvalues', 'mergejoin'):
            shutil.copytree(home / 'plugins/transforms' / name, plugins / 'transforms' / name)
        for name in ('hop-geometry-type', 'hop-geometry-inspector'):
            shutil.copytree(home / 'plugins/misc' / name, plugins / 'misc' / name)
        command = [('-Dgeometry.inspector.plugins.dir=' + str(plugins))
                   if arg.startswith('-Dgeometry.inspector.plugins.dir=') else arg for arg in command]
        subprocess.run(command, cwd=ROOT, check=True)

if __name__ == '__main__':
    main()
