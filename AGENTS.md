# Repository instructions

## CI and tests

Before changing pipelines or test setup, read the
[shared CI contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/ci-contract.md).
The documentation follows `main`; use the interfaces at this repo's actual
workflow/helper revisions and preserve existing pins and `ci-ref` values.

Run the commands below from this repository root in Bash, using Python 3, Maven
and JDK 21 (`JAVA_HOME` and `PATH` pointing to that JDK). Compatibility jobs also
use JDK 25. For headless Linux SWT tests, run Maven under `xvfb-run -a`.
Set `HOP_CI_DIR` to an absolute checkout of `hop-plugin-ci` at the helper revision
used by this repo's workflow, then prepare the same Maven repositories as CI:

```bash
CI_TEST_TMP="$(mktemp -d)"
export MAVEN_SETTINGS="$CI_TEST_TMP/maven-settings.xml"
python3 "$HOP_CI_DIR/scripts/write_maven_settings.py" --output "$MAVEN_SETTINGS"
```

### Geometry dependency and build

See [.github/workflows/ci.yml](.github/workflows/ci.yml) for the separate Geometry
build and [.github/workflows/verify.yml](.github/workflows/verify.yml) for reusable
verification. Set `GEOMETRY_REPO` to an absolute checkout of
`hop-geometry-type-plugin` (CI uses `main`). Reproduce its dependency preparation:

```bash
GEOMETRY_VERSION="$(mvn -q -f "$GEOMETRY_REPO/pom.xml" -DforceStdout help:evaluate -Dexpression=project.version)"
mvn -U -B -ntp -f "$GEOMETRY_REPO/pom.xml" -DskipTests package
```

The skip flag is the existing dependency-packaging step, not the Inspector test
command. Set `GEOMETRY_ZIP` to the resulting absolute ZIP path under the Geometry
assembly target. Install its runtime JAR as the same Maven coordinate used by CI:

```bash
export MAVEN_REPO_LOCAL="${MAVEN_USER_HOME:-$HOME/.m2}/repository"
python3 "$HOP_CI_DIR/scripts/install_maven_jar_from_zip.py" \
  --zip "$GEOMETRY_ZIP" --jar-glob 'plugins/misc/hop-geometry-type/*.jar' \
  --group-id ch.so.agi --artifact-id hop-geometry-type --version "$GEOMETRY_VERSION" \
  --maven-settings "$MAVEN_SETTINGS" --local-repository "$MAVEN_REPO_LOCAL"
mvn -s "$MAVEN_SETTINGS" -Dmaven.repo.local="$MAVEN_REPO_LOCAL" -U -B -ntp clean verify
python3 scripts/verify-package.py
```

Compatibility uses `clean test` with the same prepared dependency and settings.

### Installed plugin classloader test

Set `INSPECTOR_ZIP` to the absolute built Inspector ZIP path under
`assemblies/assemblies-hop-geometry-inspector/target`. In CI use the downloaded
canonical Inspector ZIP and the Geometry ZIP from the dependency job, without
rebuilding either candidate. With `unzip` available:

```bash
INSPECTOR_TEST_ROOT="$(mktemp -d)"
unzip -q "$GEOMETRY_ZIP" -d "$INSPECTOR_TEST_ROOT"
unzip -q "$INSPECTOR_ZIP" -d "$INSPECTOR_TEST_ROOT"
PLUGINS_DIR="$INSPECTOR_TEST_ROOT/plugins"
mvn -U -B -ntp -pl integration-tests test -Dgeometry.inspector.plugins.dir="$PLUGINS_DIR"
```

This tests installed plugin classloaders using Maven; it does not run a full Hop
client or `hop-run` pipeline. The CI snapshot publish job depends on both verify
and this installed test. The one-element `zip-descriptors` array still selects
the shared schema-version 2 bundle path.
