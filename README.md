# hop-geometry-inspector-plugin

Apache Hop 2.17 Desktop GUI plugin for visual geometry inspection (`Inspect geometries...`) on transform outputs.

## Implemented scope

- Hop Desktop GUI only (no Hop Web support)
- Transform context action: `Inspect geometries...`
- Auto-preview sampling on a pruned pipeline clone (upstream + selected transform)
- Sampling modes:
  - `FIRST`
  - `LAST`
  - `RANDOM`
- Geometry parsing chain:
  - JTS `Geometry`
  - Geometry ValueMeta (`getGeometry(...)` via geometry type plugin)
  - WKB (bytes/hex)
  - WKT/EWKT
- Viewer:
  - separate Swing window (`JMapPane`)
  - zoom/pan/reset extent tools
  - default styles for point/line/polygon families
  - status line with sampled rows, parsed features, parse errors, partial/full
- SWT fallback summary dialog if Swing/GeoTools initialization fails

## Modules

- `./hop-geometry-inspector`
  - Main GUI plugin implementation (context action/filter, sampling, parsing, viewer, fallback).
- `./assemblies/assemblies-hop-geometry-inspector`
  - Install ZIP assembly under `plugins/misc/hop-geometry-inspector`.

## Build

Full build (tests + assembly):

```bash
mvn clean verify
```

Fast plugin build (skip tests):

```bash
mvn -pl hop-geometry-inspector -am -DskipTests package
```

Build prerequisites:

- Java 17 compatible toolchain (`maven.compiler.release=17`)
- Access to:
  - Maven Central
  - OSGeo GeoTools repository (`https://repo.osgeo.org/repository/release/`)

## Install in Hop

### Option A: Manual ZIP install

1. Build install ZIP:

```bash
mvn -pl assemblies/assemblies-hop-geometry-inspector -am package
```

2. Extract into your Hop home:

```bash
unzip -o ./assemblies/assemblies-hop-geometry-inspector/target/hop-geometry-inspector-plugin-0.1.0-SNAPSHOT.zip -d "$HOP_HOME"
```

3. Resulting plugin folder:

- `$HOP_HOME/plugins/misc/hop-geometry-inspector`

### Option B: Scripted build + sync into Hop home

```bash
./scripts/dev-sync-hop-plugin.sh "$HOP_HOME"
```

Or, if `HOP_HOME` is exported:

```bash
./scripts/dev-sync-hop-plugin.sh
```

## Shell scripts

### `scripts/dev-sync-hop-plugin.sh`

Builds plugin + assembly and syncs the ZIP directly into `HOP_HOME`.

Behavior:

- runs `mvn -q -DskipTests package`
- removes target plugin folder before install to avoid stale files
- unzips `hop-geometry-inspector-plugin-<version>.zip` into `HOP_HOME`

## Fast development loop

1. Keep a local Hop installation (`$HOP_HOME`).
2. Sync latest plugin build:

```bash
./scripts/dev-sync-hop-plugin.sh "$HOP_HOME"
```

3. Restart Hop GUI to load updated plugin classes.

Notes:

- Java class reloading is limited; restart Hop after plugin class changes.
- Re-syncing removes stale plugin files before unzip.

## Tests

Current automated coverage includes:

- Unit tests:
  - geometry field detection
  - parser chain (JTS/WKT/EWKT/WKB)
  - sampling collector behavior (first/last/random)
  - feature building + parse statistics
  - preview pipeline pruning
- Integration-style tests (stubbed executor):
  - sampler service orchestration
  - pruning correctness before execution handoff

Run tests:

```bash
mvn test
```

## Troubleshooting

### Swing/GeoTools viewer does not open

The plugin falls back to an SWT summary/error dialog instead of failing hard.

### Stale plugin jars/classes in Hop home

Re-run sync script; it removes `$HOP_HOME/plugins/misc/hop-geometry-inspector` before reinstall.

### `Hop configuration file not found, not serializing ...` during tests

This message can appear in test runs and is expected in a plain Maven test environment.

## Recommended Hop GUI run configuration

- Main class: `org.apache.hop.ui.hopgui.HopGui`
- VM options (example): `-Dfile.encoding=UTF-8`

## Smoke tests

1. Pipeline editor:
   - Use/produce a geometry-compatible output field (JTS/WKT/WKB).
   - Open transform popup, confirm `Inspect geometries...` is visible.
2. Inspector options:
   - Select geometry field and sampling mode (`FIRST`, `LAST`, `RANDOM`).
   - Start with sample sizes like `50`, `200`, `1000`.
3. Viewer behavior:
   - Verify zoom/pan/reset extent.
   - Verify status metrics (rows/features/errors, partial/full).
4. Robustness:
   - Verify multiple geometry fields can be switched in viewer.
   - Verify fallback dialog appears if Swing viewer cannot initialize.
