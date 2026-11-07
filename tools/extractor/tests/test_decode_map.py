"""픽셀 배치, 원본 번호 보존, 잘린 파일 거부를 검증한다."""
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from decode_map import BLOCK_SIZE, decode_tile_pixels, extract_area_tileset_and_map


class DecodeMapTest(unittest.TestCase):
    def test_vga_planes_restore_each_pixel_column_and_row(self):
        # 각 픽셀에 좌표별로 다른 값을 넣어 행·열 뒤집힘까지 구별한다.
        source = bytes(y * 8 + half * 4 + plane
                       for plane in range(4) for y in range(8) for half in range(2))
        self.assertEqual(bytes(range(64)), decode_tile_pixels(source))

    def test_short_tile_is_rejected(self):
        with self.assertRaises(ValueError):
            decode_tile_pixels(bytes(63))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.soc = bytearray(BLOCK_SIZE)
        self.soc[:2] = b'\x01\x01'
        self.soc[2:770] = bytes(channel for color in range(256)
                                 for channel in (color % 64, 1, 2))
        # 0번과 마지막 타일을 서로 다른 색으로 만든다. 255도 배경에서는 불투명하다.
        self.soc[770:834] = bytes([5]) * 64
        self.soc[770 + 999 * 64:770 + 1000 * 64] = bytes([255]) * 64
        self.header = bytearray(48)
        self.header[2:4] = b'\x01\x01'
        self.header[4:12] = b'SA00.PCX'
        self.cells = [struct.pack('<HH', 0x6000 | 999, 20)] + [struct.pack('<HH', 0, 0)] * 999
        (self.root / 'source.soc').write_bytes(self.soc)
        (self.root / 'source.sda').write_bytes(struct.pack('<II', 1, 0))
        (self.root / 'source.mda').write_bytes(struct.pack('<II', 1, 0))
        self.write_map()

    def write_map(self):
        (self.root / 'source.map').write_bytes(self.header + b''.join(self.cells))

    def extract(self):
        return extract_area_tileset_and_map(
            self.root / 'source.soc', self.root / 'source.sda',
            self.root / 'source.map', self.root / 'source.mda',
            self.root / 'sprites', self.root / 'data', 0, self.root / 'preview.png')

    def test_preserves_zero_last_tile_flags_and_opaque_palette_255(self):
        result = self.extract()
        self.assertEqual((40, 25, 8, 8), tuple(result[k] for k in ('width', 'height', 'tile_width', 'tile_height')))
        self.assertEqual([1000] + [1] * 999, result['layer1'])
        self.assertEqual([0] * 1000, result['layer2'])
        with Image.open(self.root / 'sprites/area00_tileset.png') as atlas:
            self.assertEqual((320, 208), atlas.size)
            self.assertEqual((0, 0, 0, 0), atlas.getpixel((0, 0)))
        with Image.open(self.root / 'preview.png') as preview:
            self.assertEqual((320, 200), preview.size)
            self.assertEqual((252, 4, 8, 255), preview.getpixel((0, 0)))
            self.assertEqual((20, 4, 8, 255), preview.getpixel((8, 0)))
            self.assertEqual((20, 4, 8, 255), preview.getpixel((319, 199)))
        self.assertEqual(result, json.loads((self.root / 'data/area00_map.json').read_text()))

    def test_missing_tile_never_falls_back_to_invented_floor(self):
        self.cells[0] = struct.pack('<HH', 1000, 0)
        self.write_map()
        with self.assertRaisesRegex(ValueError, '범위'):
            self.extract()

    def test_truncated_cells_are_rejected(self):
        self.cells.pop()
        self.write_map()
        with self.assertRaisesRegex(ValueError, '잘렸'):
            self.extract()

    def test_truncated_soc_is_rejected(self):
        (self.root / 'source.soc').write_bytes(self.soc[:-1])
        with self.assertRaisesRegex(ValueError, '구조'):
            self.extract()

    def test_unknown_tileset_is_rejected(self):
        self.header[4:12] = b'SB00.PCX'
        self.write_map()
        with self.assertRaisesRegex(ValueError, '이름'):
            self.extract()


if __name__ == '__main__':
    unittest.main()
