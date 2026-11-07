import struct
import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from decode_encounter import decode_vis


def fixture(first,second):
    payload=first+second
    start=785
    group=struct.pack('<3H',0,2,len(payload))
    return struct.pack('<HH3s',1,3,b'VIS')+bytes(768)+group+struct.pack('<I',start)+group+struct.pack('<2H',len(first),len(second))+payload


class EncounterTest(unittest.TestCase):
    def test_frames_apply_differences_to_previous_screen(self):
        first=bytes(4)+bytes([0,2,5,128,0,0])
        second=bytes(4)+bytes([129,1,9,128,0,0])
        _,frames=decode_vis(fixture(first,second))
        self.assertEqual(frames[0][:3],bytes([5,5,0]))
        self.assertEqual(frames[1][:3],bytes([5,9,0]))

    def test_unchanged_frame_keeps_previous_pixels(self):
        first=bytes(4)+bytes([1,7,128,0,0])
        _,frames=decode_vis(fixture(first,b''))
        self.assertEqual(frames[0],frames[1])

    def test_truncated_copy_and_unfinished_frame_fail(self):
        for data in (bytes(4)+bytes([5,1]),bytes(4)+bytes([0,1,7])):
            with self.assertRaises(ValueError):decode_vis(fixture(data,b''))

    def test_frame_cannot_write_beyond_screen(self):
        data=bytes(4)+bytes([128,255,127,128,255,127,10])+bytes(10)+bytes([128,0,0])
        with self.assertRaises(ValueError):decode_vis(fixture(data,b''))
