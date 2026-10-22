"""Host harness safety checks; no device or private input required."""
import tempfile
import unittest
import zipfile
from pathlib import Path

from memora_qa import ROOT, extract_safe, message_records, require_emulator_serial


class HarnessSafetyTest(unittest.TestCase):
    def test_rejects_physical_serial_and_missing_serial(self):
        for serial in ("", "phone123", "emulator-foo", "emulator-5580 extra"):
            with self.assertRaises(ValueError):
                require_emulator_serial(serial)
        require_emulator_serial("emulator-5580")

    def test_rejects_zip_escape_absolute_and_windows_paths_before_extracting(self):
        for name in ("../escape", "/escape", "C:/escape", "nested\\..\\escape"):
            (ROOT / "artifacts").mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(dir=ROOT / "artifacts") as directory:
                root = Path(directory)
                archive = root / "fixture.zip"
                with zipfile.ZipFile(archive, "w") as z:
                    z.writestr("safe.txt", "synthetic")
                    z.writestr(name, "escape")
                with self.assertRaises(ValueError):
                    extract_safe(archive, root / "out")
                self.assertFalse((root / "out" / "safe.txt").exists())

    def test_complete_records_preserve_multiline_unicode_and_last_record(self):
        text = "03/04/2026, 10:00 - Maya: QA-MSG-001 مرحبا\ncontinued\n13/10/2026, 11:00 - Alex: QA-MSG-002 café\n"
        records = message_records(text)
        self.assertEqual(2, len(records))
        self.assertIn("مرحبا\ncontinued", records[0])
        self.assertTrue(records[1].endswith("café"))


if __name__ == "__main__":
    unittest.main()
