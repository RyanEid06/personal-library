"""Synthetic-only host helpers. All device operations verify a dedicated QA AVD."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts" / "qa-v101"
ADB = ROOT / ".tools" / "android-sdk" / "platform-tools" / "adb.exe"
FIXTURE_HASH = "12EEF02A083C45B9440555E437CBCAB90DDB3BB109F5CD6DF274AADFD9CDD9C2"


def require_emulator_serial(serial):
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("An explicit emulator serial is required; physical devices are forbidden")


def extract_safe(archive, destination):
    destination = Path(destination).resolve()
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            name = entry.filename
            path = PurePosixPath(name)
            if ("\\" in name or ":" in name or path.is_absolute() or ".." in path.parts
                    or (entry.external_attr >> 16) & 0o170000 == 0o120000
                    or entry.file_size > 16 * 1024 * 1024):
                raise ValueError(f"Unsafe fixture member: {name}")
        if sum(e.file_size for e in z.infolist()) > 32 * 1024 * 1024:
            raise ValueError("Unexpected fixture size")
        z.extractall(destination)


def message_records(text):
    starts = list(re.finditer(r"(?m)^\d{2}/\d{2}/\d{4}, \d{2}:\d{2} - ", text))
    return [text[m.start():starts[i + 1].start() if i + 1 < len(starts) else len(text)].rstrip("\r\n")
            for i, m in enumerate(starts)]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()


def validate_fixtures(archive):
    if sha(archive) != FIXTURE_HASH:
        raise ValueError("Supplied ZIP hash changed; do not import until reviewed")
    destination = ARTIFACTS / "fixtures"
    extract_safe(archive, destination)
    files = {p.name: p for p in destination.rglob("*") if p.is_file()}
    full = message_records(files["Memora_QA_WhatsApp_200.txt"].read_text(encoding="utf-8-sig"))
    part = message_records(files["Memora_QA_WhatsApp_160.txt"].read_text(encoding="utf-8-sig"))
    assert len(full) == 200 and len(part) == 160, "Message-header counts differ"
    assert full[:160] == part, "First160 complete records differ"
    markers = re.findall(r"QA-MSG-\d{3}", "\n".join(full))
    assert sorted(markers) == [f"QA-MSG-{i:03}" for i in range(1, 201)], "Marker uniqueness differs"
    report = {"archive_sha256": sha(archive), "messages": len(full), "incremental": len(full)-len(part),
              "first160_identical": True, "members": {str(p.relative_to(destination)): sha(p)
              for p in destination.rglob("*") if p.is_file()}}
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    (ARTIFACTS / "fixture-validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


class Emulator:
    def __init__(self, serial):
        require_emulator_serial(serial)
        self.serial = serial
        if self.run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise ValueError("Target has not proved it is an emulator")
        if "Memora-QA-API37" not in self.run("emu", "avd", "name"):
            raise ValueError("Target is not this audit's disposable AVD")

    def run(self, *args, binary=False, timeout=60):
        result = subprocess.run([str(ADB), "-s", self.serial, *map(str, args)],
                                capture_output=True, timeout=timeout, check=True)
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    def snapshot(self, label):
        if not re.fullmatch(r"[A-Za-z0-9_-]+", label):
            raise ValueError("Invalid evidence label")
        folder = ARTIFACTS / "ui"
        folder.mkdir(parents=True, exist_ok=True)
        self.run("shell", "uiautomator", "dump", "/sdcard/Download/memora-qa-window.xml")
        xml = self.run("exec-out", "cat", "/sdcard/Download/memora-qa-window.xml")
        (folder / f"{label}.xml").write_text(xml, encoding="utf-8")
        (folder / f"{label}.png").write_bytes(self.run("exec-out", "screencap", "-p", binary=True))
        tree = ET.fromstring(xml)
        return [n.attrib for n in tree.iter("node") if n.get("text") or n.get("content-desc") or n.get("class", "").endswith("EditText")]

    def tap(self, label, match):
        nodes = self.snapshot(label)
        matches = [n for n in nodes if match in (n.get("text"), n.get("content-desc"), n.get("resource-id"))]
        if len(matches) != 1:
            raise ValueError(f"Expected one UI target {match!r}; found {len(matches)}")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[0]["bounds"]))
        return self.run("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)

    def scroll_to(self, label, match):
        for attempt in range(7):
            nodes = self.snapshot(f"{label}-{attempt}")
            if any(match in (n.get("text"), n.get("content-desc")) for n in nodes):
                return self.tap(f"{label}-target", match)
            tree = ET.parse(ARTIFACTS / "ui" / f"{label}-{attempt}.xml")
            containers = [n for n in tree.iter("node") if n.get("scrollable") == "true"]
            if not containers:
                raise ValueError("No scrollable container in current UI")
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", containers[0].get("bounds")))
            self.run("shell", "input", "swipe", (x1+x2)//2, y1+(y2-y1)*4//5,
                     (x1+x2)//2, y1+(y2-y1)//5, 300)
        raise ValueError(f"Target not found after bounded scrolling: {match}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["fixtures", "snapshot", "tap", "scroll"])
    parser.add_argument("value")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--match")
    args = parser.parse_args()
    if args.action == "fixtures":
        print(json.dumps(validate_fixtures(Path(args.value)), indent=2))
    else:
        emulator = Emulator(args.serial)
        result = (emulator.tap(args.value, args.match) if args.action == "tap" else
                  emulator.scroll_to(args.value, args.match) if args.action == "scroll" else [
            {k: n[k] for k in ("text", "content-desc", "resource-id", "bounds", "class") if n.get(k)}
            for n in emulator.snapshot(args.value)])
        print(json.dumps(result, ensure_ascii=False, indent=2))
