#!/usr/bin/env python3
"""Validate the canonical Geometry Inspector installation ZIP."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
POM_NAMESPACE = "{http://maven.apache.org/POM/4.0.0}"
PLUGIN_ROOT = "plugins/misc/hop-geometry-inspector"


def project_version() -> str:
    root = ET.parse(ROOT / "pom.xml").getroot()
    version = root.findtext(f"{POM_NAMESPACE}version") or root.findtext("version")
    if not version:
        raise SystemExit("Could not resolve project.version from pom.xml")
    return version


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_safe_path(name: str) -> None:
    path = Path(name)
    if path.is_absolute() or ".." in path.parts:
        raise SystemExit(f"ZIP contains an unsafe path: {name}")


def validate_plugin_jar(name: str, content: bytes) -> None:
    with zipfile.ZipFile(io.BytesIO(content)) as jar:
        if jar.testzip() is not None:
            raise SystemExit(f"Plugin JAR is corrupt: {name}")
        entries = jar.namelist()
        classes = [entry for entry in entries if entry.endswith(".class")]
        forbidden_prefixes = ("org/apache/hop/", "org/eclipse/swt/")
        if any(entry.startswith(forbidden_prefixes) for entry in classes):
            raise SystemExit("Plugin JAR embeds Hop or SWT classes")

        required = {
            "ch/so/agi/hop/geometry/inspector/GeometryInspectorGuiPlugin.class",
            "META-INF/jandex.idx",
            "ch/so/agi/hop/geometry/inspector/ui/toolbar-icons/map.svg",
            "ch/so/agi/hop/geometry/inspector/ui/toolbar-icons/zoom-in.svg",
            "ch/so/agi/hop/geometry/inspector/ui/toolbar-icons/zoom-out.svg",
        }
        missing = sorted(required - set(entries))
        if missing:
            raise SystemExit(f"Plugin JAR is missing required entries: {missing}")


def validate(path: Path, version: str) -> dict[str, object]:
    expected_name = f"hop-geometry-inspector-plugin-{version}.zip"
    if not path.is_file():
        raise SystemExit(f"Missing package ZIP: {path}")
    if path.name != expected_name:
        raise SystemExit(f"Unexpected package name {path.name!r}; expected {expected_name!r}")

    plugin_jar_name = f"{PLUGIN_ROOT}/hop-geometry-inspector-{version}.jar"
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise SystemExit(f"Package ZIP is corrupt: {path}")
        entries = archive.namelist()
        for entry in entries:
            check_safe_path(entry)

        files = {entry for entry in entries if not entry.endswith("/")}
        plugin_jars = [
            entry
            for entry in files
            if entry.startswith(f"{PLUGIN_ROOT}/")
            and entry.endswith(".jar")
            and "/lib/" not in entry
        ]
        if plugin_jars != [plugin_jar_name]:
            raise SystemExit(f"Expected exactly one plugin JAR {plugin_jar_name!r}, found {plugin_jars}")

        libraries = sorted(
            entry for entry in files if entry.startswith(f"{PLUGIN_ROOT}/lib/") and entry.endswith(".jar")
        )
        required_libraries = {
            f"{PLUGIN_ROOT}/lib/gt-main.jar",
            f"{PLUGIN_ROOT}/lib/gt-render.jar",
            f"{PLUGIN_ROOT}/lib/gt-wms.jar",
            f"{PLUGIN_ROOT}/lib/gt-wmts.jar",
            f"{PLUGIN_ROOT}/lib/gt-tile-client.jar",
            f"{PLUGIN_ROOT}/lib/gt-epsg-hsql.jar",
            f"{PLUGIN_ROOT}/lib/indriya.jar",
            f"{PLUGIN_ROOT}/lib/unit-api.jar",
            f"{PLUGIN_ROOT}/lib/systems-common.jar",
        }
        missing = sorted(required_libraries - set(libraries))
        if missing:
            raise SystemExit(f"Package is missing required runtime libraries: {missing}")

        forbidden_libraries = {
            f"{PLUGIN_ROOT}/lib/jts-core.jar",
            f"{PLUGIN_ROOT}/lib/dependencies.xml",
        }
        unexpected = sorted(forbidden_libraries & set(files))
        if unexpected:
            raise SystemExit(f"Package contains shared-runtime files that must be provided by Geometry Type: {unexpected}")

        # Assembly names omit versions; inspect each GeoTools JAR manifest instead.
        expected_geotools = ET.parse(ROOT / "pom.xml").getroot().findtext(
            f"{POM_NAMESPACE}properties/{POM_NAMESPACE}geotools.version"
        )
        for library in libraries:
            if Path(library).name.startswith("gt-"):
                with zipfile.ZipFile(io.BytesIO(archive.read(library))) as jar:
                    manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
                    if f"Project-Version: {expected_geotools}" not in manifest.splitlines():
                        raise SystemExit(f"Unexpected GeoTools version in {library}")

        validate_plugin_jar(plugin_jar_name, archive.read(plugin_jar_name))

    return {
        "version": version,
        "zipFile": str(path),
        "sha256": sha256(path),
        "pluginJar": plugin_jar_name,
        "libraryCount": len(libraries),
    }


def main() -> int:
    version = project_version()
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--zip",
        type=Path,
        default=ROOT / f"assemblies/assemblies-hop-geometry-inspector/target/hop-geometry-inspector-plugin-{version}.zip",
    )
    args = parser.parse_args()
    report = validate(args.zip, version)
    output = ROOT / "target/package-verification.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
