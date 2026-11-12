"""원작 이벤트 주소 기록으로 Python 파서도 독립적으로 확인한다."""
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from event_archive import EventArchive

ROOT=Path(__file__).resolve().parents[3]


class EventArchiveTest(unittest.TestCase):
    def test_native_entries_including_unmatched_arguments(self):
        archives={i:EventArchive((ROOT/f'assets/data/events/area{i:02}.gm').read_bytes()) for i in range(6)}
        report=json.loads((ROOT/'docs/restoration/event-entry-native.json').read_text())
        for area,group,argument,offset,opcode in report['cases']:
            archive=archives[area]
            self.assertEqual(offset,archive.resolve(group,argument))
            self.assertEqual(opcode,archive.code[offset])

    def test_truncated_and_invalid_group_tables(self):
        raw=(ROOT/'assets/data/events/area00.gm').read_bytes()
        for broken in (b'',raw[:5],raw[:-1],raw[:6]+b'\xff\xff'+raw[8:]):
            with self.assertRaises(ValueError):EventArchive(broken)
        archive=EventArchive(raw)
        for group,argument in ((-1,0),(len(archive.groups),0),(0,-1),(0,65536)):
            with self.assertRaises(ValueError):archive.resolve(group,argument)
