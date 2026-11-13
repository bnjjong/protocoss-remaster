"""편성 파일의 빈 슬롯·부호·잘린 데이터가 적 배치를 바꾸지 않게 검사한다."""
from pathlib import Path
import struct
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from first_battle import formations


class FirstBattleTest(unittest.TestCase):
    def test_signed_positions_and_one_based_ids_preserve_repeated_enemies(self):
        raw=struct.pack('<15hBB',-2,30,0,0,0,80,100,0,0,0,1,1,0,0,0,23,255)
        self.assertEqual([dict(id=0,background=23,enemies=[dict(monster=0,x=-2,y=80),dict(monster=0,x=30,y=100)])],formations(raw))

    def test_first_empty_slot_ends_the_formation(self):
        raw=struct.pack('<15hBB',*([0]*10),0,123,0,0,0,23,0)
        self.assertEqual([],formations(raw)[0]['enemies'])

    def test_truncated_records_and_invalid_monster_ids_fail(self):
        with self.assertRaises(ValueError):
            formations(bytes(31))
        for invalid in (-1,124):
            raw=struct.pack('<15hBB',*([0]*10),invalid,0,0,0,0,23,0)
            with self.assertRaises(ValueError):
                formations(raw)
