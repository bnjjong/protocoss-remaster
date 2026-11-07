"""원작의 평면·열 순서, 투명 구간, 컨테이너 범위 검사를 검증한다."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from decode_user_sprite import decode_frame, extract_characters, extract_kaman, parse_user_spr


def fixture_frame():
    return struct.pack('<HH', 8, 4) + bytes([
        2, 2, 0, 1, 0, 2, 1, 255, 1, 1, 2, 10, 11,
        2, 1, 0, 1, 20, 1, 3, 1, 21,
        2, 1, 1, 1, 30, 1, 2, 1, 31,
        2, 1, 2, 1, 40, 1, 0, 1, 41,
    ])


def fixture_container(groups):
    blobs = []
    for frames in groups:
        offsets, size = [], 0
        for frame in frames:
            offsets.append(size)
            size += len(frame)
        blobs.append(struct.pack('<I', len(frames))
                     + struct.pack(f'<{len(frames)}I', *offsets)
                     + struct.pack('<I', size) + b''.join(frames))
    starts, start = [], len(groups) * 4
    for blob in blobs:
        starts.append(start)
        start += len(blob)
    return struct.pack(f'<{len(starts)}I', *starts) + b''.join(blobs)


class UserSpriteTest(unittest.TestCase):
    def test_each_plane_and_column_restore_pixels_and_transparency(self):
        frame = decode_frame(fixture_frame())
        expected = {(0, 0): 0, (0, 3): 255, (4, 1): 10, (4, 2): 11,
                    (1, 0): 20, (5, 3): 21, (2, 1): 30, (6, 2): 31,
                    (3, 2): 40, (7, 0): 41}
        self.assertEqual((8, 4), (frame.width, frame.height))
        for y in range(4):
            for x in range(8):
                pixel = y * 8 + x
                self.assertEqual(expected.get((x, y), 0), frame.indices[pixel])
                self.assertEqual(255 if (x, y) in expected else 0, frame.alpha[pixel])

    def test_palette_indices_zero_and_255_are_opaque_when_written(self):
        palette = bytearray(768)
        palette[:3] = bytes([1, 2, 3])
        palette[-3:] = bytes([2, 3, 4])
        image = decode_frame(fixture_frame()).to_image(palette)
        self.assertEqual((4, 8, 12, 255), image.getpixel((0, 0)))
        self.assertEqual((8, 12, 16, 255), image.getpixel((0, 3)))
        self.assertEqual((0, 0, 0, 0), image.getpixel((0, 1)))

    def test_zero_count_keeps_the_current_column_position(self):
        raw = struct.pack('<HH', 8, 4) + bytes([
            2, 2, 1, 0, 1, 1, 7, 0,
            2, 0, 0, 2, 0, 0, 2, 0, 0,
        ])
        frame = decode_frame(raw)
        self.assertEqual(7, frame.indices[2 * 8])
        self.assertEqual(255, frame.alpha[2 * 8])
        self.assertEqual(255, sum(frame.alpha))

    def test_declared_columns_must_match_width(self):
        raw = bytearray(fixture_frame())
        raw[4] = 1
        with self.assertRaisesRegex(ValueError, '열 수'):
            decode_frame(raw)

    def test_span_cannot_write_below_the_frame(self):
        with self.assertRaisesRegex(ValueError, '높이'):
            decode_frame(struct.pack('<HH', 8, 4) + bytes([2, 1, 3, 2, 1, 2]))

    def test_truncated_pixel_span_is_rejected(self):
        with self.assertRaisesRegex(ValueError, '픽셀 구간 데이터'):
            decode_frame(struct.pack('<HH', 8, 4) + bytes([2, 1, 0, 3, 1]))

    def test_truncated_headers_and_spans_are_rejected(self):
        for raw in (b'', bytes(3), fixture_frame()[:-1]):
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError):
                    decode_frame(raw)

    def test_zero_dimensions_and_unread_trailing_bytes_are_rejected(self):
        for raw in (struct.pack('<HH', 0, 4), fixture_frame() + b'\0'):
            with self.subTest(raw=raw):
                with self.assertRaises(ValueError):
                    decode_frame(raw)

    def test_invalid_palette_is_rejected(self):
        frame = decode_frame(fixture_frame())
        for palette in (bytes(767), bytes([64]) + bytes(767)):
            with self.assertRaises(ValueError):
                frame.to_image(palette)

    def test_nested_offsets_retain_absolute_source_location(self):
        raw = fixture_frame()
        source = fixture_container([[raw, raw], [raw]])
        groups = parse_user_spr(source)
        self.assertEqual([2, 1], [len(group) for group in groups])
        self.assertEqual(24, groups[0][0].source_offset)
        for group in groups:
            for frame in group:
                self.assertEqual(raw, frame.data)
                self.assertEqual(raw, source[frame.source_offset:frame.source_offset + len(raw)])

    def test_invalid_container_lengths_and_offsets_are_rejected(self):
        source = fixture_container([[fixture_frame()], [fixture_frame()]])
        invalid = [b'', source[:-1]]
        for offset, value in ((0, 3), (4, 8), (12, 1), (16, 1)):
            changed = bytearray(source)
            struct.pack_into('<I', changed, offset, value)
            invalid.append(changed)
        for raw in invalid:
            with self.subTest(raw=raw[:20]):
                with self.assertRaises(ValueError):
                    parse_user_spr(raw)

    def test_export_manifest_and_demo_copy_preserve_the_first_frame(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'game/GRP').mkdir(parents=True)
            (root / 'game/DATA').mkdir()
            source = fixture_container([[fixture_frame(), fixture_frame()], [fixture_frame()]])
            (root / 'game/GRP/USER.SPR').write_bytes(source)
            (root / 'game/DATA/PROTOCOS.PAL').write_bytes(bytes(768))
            info = extract_kaman(root / 'game', root / 'output')
            directory = root / 'output/assets/sprites/user/character_00'
            self.assertEqual(2, info['frame_count'])
            self.assertEqual(2, info['character_count'])
            self.assertEqual(hashlib.sha256(source).hexdigest(), info['source_sha256'])
            self.assertEqual(info, json.loads((directory / 'manifest.json').read_text()))
            self.assertEqual((directory / 'frame_000.png').read_bytes(),
                             (root / 'output/assets/sprites/kaman_test.png').read_bytes())
            with Image.open(directory / 'frame_001.png') as image:
                self.assertEqual((8, 4), image.size)

    def test_bad_later_frame_does_not_replace_existing_demo_texture(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'game/GRP').mkdir(parents=True)
            (root / 'game/DATA').mkdir()
            (root / 'game/GRP/USER.SPR').write_bytes(fixture_container([[fixture_frame(), fixture_frame()[:-1]]]))
            (root / 'game/DATA/PROTOCOS.PAL').write_bytes(bytes(768))
            target = root / 'output/assets/sprites/kaman_test.png'
            target.parent.mkdir(parents=True)
            target.write_bytes(b'keep-existing-file')
            with self.assertRaises(ValueError):
                extract_kaman(root / 'game', root / 'output')
            self.assertEqual(b'keep-existing-file', target.read_bytes())
            self.assertFalse((target.parent / 'user').exists())


    def test_index_alpha_keeps_color_numbers_when_palette_colors_match(self):
        frame = decode_frame(fixture_frame())
        rgba = frame.to_image(bytes(768))
        self.assertEqual(rgba.getpixel((0, 0)), rgba.getpixel((0, 3)))
        indices = frame.to_index_alpha_image()
        self.assertEqual('LA', indices.mode)
        self.assertEqual((0, 255), indices.getpixel((0, 0)))
        self.assertEqual((255, 255), indices.getpixel((0, 3)))
        self.assertEqual((0, 0), indices.getpixel((0, 1)))

    def test_selecting_other_character_keeps_kaman_and_uses_its_own_offsets(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'game/GRP').mkdir(parents=True)
            (root / 'game/DATA').mkdir()
            other = bytearray(fixture_frame())
            other[8] = 63
            source = fixture_container([[fixture_frame(), fixture_frame()], [bytes(other)]])
            (root / 'game/GRP/USER.SPR').write_bytes(source)
            (root / 'game/DATA/PROTOCOS.PAL').write_bytes(bytes(768))
            demo = root / 'output/assets/sprites/kaman_test.png'
            demo.parent.mkdir(parents=True)
            demo.write_bytes(b'keep-kaman')
            result = extract_characters(root / 'game', root / 'output', [1])
            self.assertEqual([1], [row['character_index'] for row in result])
            self.assertEqual(1, result[0]['frame_count'])
            self.assertEqual(parse_user_spr(source)[1][0].source_offset,
                             result[0]['frames'][0]['source_offset'])
            directory = demo.parent / 'user/character_01'
            with Image.open(directory / 'indices/frame_000.png') as image:
                self.assertEqual((63, 255), image.getpixel((0, 0)))
            self.assertFalse((demo.parent / 'user/character_00').exists())
            self.assertEqual(b'keep-kaman', demo.read_bytes())

    def test_export_all_characters_creates_separate_manifests_and_index_images(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'game/GRP').mkdir(parents=True)
            (root / 'game/DATA').mkdir()
            (root / 'game/GRP/USER.SPR').write_bytes(
                fixture_container([[fixture_frame(), fixture_frame()], [fixture_frame()]]))
            (root / 'game/DATA/PROTOCOS.PAL').write_bytes(bytes(768))
            result = extract_characters(root / 'game', root / 'output',
                                        preview_path=root / 'overview.png', preview_dir=root / 'previews')
            self.assertEqual([2, 1], [row['frame_count'] for row in result])
            for manifest in result:
                directory = root / f"output/assets/sprites/user/character_{manifest['character_index']:02d}"
                self.assertEqual(manifest, json.loads((directory / 'manifest.json').read_text()))
                for frame in manifest['frames']:
                    with Image.open(directory / frame['index_alpha_file']) as image:
                        self.assertEqual('LA', image.mode)
                        self.assertEqual((255, 255), image.getpixel((0, 3)))
            self.assertTrue((root / 'overview.png').is_file())
            self.assertEqual(2, len(list((root / 'previews').glob('*.png'))))

    def test_invalid_selection_and_broken_other_character_do_not_write_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'game/GRP').mkdir(parents=True)
            (root / 'game/DATA').mkdir()
            source = root / 'game/GRP/USER.SPR'
            source.write_bytes(fixture_container([[fixture_frame()], [fixture_frame()]]))
            (root / 'game/DATA/PROTOCOS.PAL').write_bytes(bytes(768))
            for selection in ([], [-1], [2], [0, 0]):
                with self.subTest(selection=selection):
                    with self.assertRaises(ValueError):
                        extract_characters(root / 'game', root / 'output', selection)
            self.assertFalse((root / 'output').exists())
            target = root / 'output/assets/sprites/user/character_00/frame_000.png'
            target.parent.mkdir(parents=True)
            target.write_bytes(b'keep-first-character')
            source.write_bytes(fixture_container([[fixture_frame()], [fixture_frame()[:-1]]]))
            with self.assertRaises(ValueError):
                extract_characters(root / 'game', root / 'output')
            self.assertEqual(b'keep-first-character', target.read_bytes())
            self.assertFalse((target.parent.parent / 'character_01').exists())


if __name__ == '__main__':
    unittest.main()
