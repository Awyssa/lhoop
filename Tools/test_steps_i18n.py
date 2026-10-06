"""Regression coverage for every shipped locale of the steps feature."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"


def signature(value):
    result = {}
    next_index = 1
    for match in re.finditer(r"%(?:(\d+)\$)?(lld|[sd@])", value):
        index, kind = match.groups()
        index = int(index) if index else next_index
        next_index += 1
        if index in result and result[index] != kind:
            raise ValueError(f"Inconsistent placeholder {index}")
        result[index] = kind
    return result

class StepsTranslationsTests(unittest.TestCase):
    def test_android_all_shipped_locales(self):
        source = ET.parse(RES / "values/steps_view.xml").getroot()
        keys = {entry.attrib["name"]: entry for entry in source}
        directories = [RES / "values", *sorted(RES.glob("values-*"))]
        for directory in directories:
            if directory != RES / "values" and not (directory / "strings.xml").exists():
                continue
            with self.subTest(locale=directory.name):
                entries = {}
                for path in directory.glob("*.xml"):
                    for entry in ET.parse(path).getroot():
                        name = entry.get("name")
                        if name not in keys:
                            continue
                        self.assertNotIn(name, entries, f"Duplicate {name} in {directory}")
                        entries[name] = entry
                self.assertEqual(set(entries), set(keys))
                for name, original in keys.items():
                    translated = entries[name]
                    self.assertEqual(original.tag, translated.tag)
                    if original.tag == "string-array":
                        self.assertEqual(len(original), len(translated))
                        self.assertTrue(all(item.text for item in translated))
                    else:
                        self.assertTrue(translated.text)
                        self.assertEqual(signature(original.text), signature(translated.text), name)


if __name__ == "__main__":
    unittest.main()

