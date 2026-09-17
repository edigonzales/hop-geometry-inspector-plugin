#!/usr/bin/env python3
"""Check the GUI handbook contract not covered by transform-only Maven doclint."""
from pathlib import Path
import xml.etree.ElementTree as ET
import json
ROOT = Path(__file__).resolve().parents[1]
text = (ROOT / 'docs/inspector/data-inspector.adoc').read_text()
for section in ('Description', 'Input', 'Options', 'Output', 'Caches', 'Replay', 'Supported engines', 'Examples', 'Error handling', 'Limitations', 'Troubleshooting'):
    assert '\n== ' + section + '\n' in text, 'Missing section: ' + section
assert '[[data-inspector]]' in text and '[.gui-mockup]' in text
expected = json.loads((ROOT / 'e2e/expected/examples.json').read_text())
for path in (ROOT / 'examples').rglob('*.hpl'):
    pipeline = ET.parse(path).getroot()
    assert pipeline.tag == 'pipeline'
    assert path.with_name('README.md').exists(), path
    assert path.parent.name in expected, 'Missing automated expectations: ' + str(path)
    names = {t.findtext('name') for t in pipeline.findall('transform')}
    assert set(expected[path.parent.name]).issubset(names)
    for hop in pipeline.findall('order/hop'):
        assert hop.findtext('from') in names and hop.findtext('to') in names
print('Inspector documentation and examples: OK')
