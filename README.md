# hop-geometry-inspector-plugin

**Data Inspector** for Apache Hop 2.19 Desktop. Right-click a transform and choose
**Inspect data...** to inspect an isolated pipeline run.

## Features

- Complete row tables, typed sorting, search, field filters and geometry details.
- Multiple result layers with linked map/table selection; no reprojection.
- Explicit stream sampling (FIRST/LAST/RANDOM) and persistent binary caches.
- Conservative cache replay with a preview of the transforms to execute.
- SWT/GeoTools map with the swisstopo grey pixel map as its default WMTS background.

See the [handbook](https://edigonzales.github.io/hop-geometry-inspector-plugin/data-inspector/main/),
[handbook source](docs/inspector/data-inspector.adoc),
[architecture](docs/development/architecture.adoc) and [examples](examples/README.md)
for behavior, restrictions and troubleshooting.

## Requirements and installation

Use Hop **2.19**, Java **21**, and the matching
[Geometry Type plugin](https://github.com/edigonzales/hop-geometry-type-plugin).
Extract both plugin ZIPs into your Hop installation and restart Hop Desktop.
The Inspector remains installed at `plugins/misc/hop-geometry-inspector`.
Plugin, Maven and settings IDs remain compatible with the Geometry Inspector.
Hop Web and remote execution are not supported.

## Build and development

Follow [AGENTS.md](AGENTS.md) to prepare the Geometry dependency and CI Maven settings.
The shared parent is `ch.so.agi:hop-plugin-parent:0.1.0-SNAPSHOT`; GeoTools resolves
from OSGeo. Snapshot dependencies resolve from `https://jars.interlis.guru/snapshots/`.

```bash
mvn clean verify
python3 scripts/verify-package.py
python3 scripts/check-docs.py
python3 scripts/build-docs-site.py
```

For a disposable development Hop installation, `scripts/dev-sync-hop-plugin.sh "$HOP_HOME"`
builds and replaces the installed Inspector folder. Restart Hop after syncing.
That convenience script skips tests; it does not replace verification.

## Artifacts and modules

- `hop-geometry-inspector`: GUI, capture, row stores and replay.
- `assemblies/assemblies-hop-geometry-inspector`: install ZIP under `target/`.
- `integration-tests`: tests against installed, canonical plugin ZIPs.
- `examples`: directly openable user pipelines; `e2e`: automated expectations.

Geometry Type and JTS are supplied by the shared Geometry runtime classloader,
not duplicated in the Inspector ZIP.

## CI and publication

The [shared CI contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/ci-contract.md)
verifies Java 21/25 compatibility on Linux, macOS and Windows. Ubuntu/Java 21 creates
the canonical ZIP. Package and installed-classloader checks gate snapshot publication;
publication never rebuilds the candidate. Existing workflow references and `ci-ref`
values are preserved. Pull requests do not publish artifacts.

Documentation builds the exact checkout in a separate workflow. `main` deploys to
GitHub Pages; the repository's Pages source must be set to **GitHub Actions**.
Local documentation builds include uncommitted working files.

## License

See [LICENSE](LICENSE).
