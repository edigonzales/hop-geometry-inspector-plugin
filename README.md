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

## CRS behavior

The geometry inspector does not reproject geometries.

- Parsed geometries are rendered with their original numeric coordinates.
- The plugin inspects the sampled geometries for positive SRIDs (`geometry.getSRID() > 0`).
- A usable CRS is only established when all renderable sampled geometries share exactly one positive SRID and GeoTools can decode it as `EPSG:<srid>`.
- In that case, the sample feature type and sample extent are tagged with that decoded CRS.
- If sampled geometries have no SRID, mixed SRIDs, or an SRID that cannot be decoded, the inspector still renders the geometry overlay, but it has no usable common CRS.

Practical consequences:

- Overlay rendering:
  - Uses the coordinates of the displayed geometries as-is.
  - No transformation between per-row SRIDs and a target map CRS is performed.
  - Mixed CRS samples can therefore render in inconsistent positions relative to each other.
- Selection details:
  - If the selected geometry has its own positive SRID, the UI shows that as `EPSG:<srid>`.
  - Otherwise the UI falls back to the detected sample CRS status or `No SRID`.
- WMS background:
  - The background map is only enabled when the sample has a consistent, decodable CRS.
  - The WMS `GetMap` request then uses exactly that detected SRID as `EPSG:<srid>`.
  - No separate reprojection step is performed before requesting the WMS.
  - If the sample has no usable common CRS, the WMS background stays unavailable.

In short:

- The inspector does not always use the CRS of the currently displayed single geometry.
- For the WMS, it uses the one common CRS detected for the whole sampled result set.
- If there is no single consistent CRS across the sample, no WMS request is sent.

## Troubleshooting

### Viewer does not open

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
