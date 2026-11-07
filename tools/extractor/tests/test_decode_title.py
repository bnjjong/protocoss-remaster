import struct
import unittest
from decode_title import parse_img, decode_img_frame


class TitleTest(unittest.TestCase):
    def test_headerless_planes_keep_written_black_separate_from_transparency(self):
        raw = bytes([1, 1, 1, 1, 0, 1, 1, 0, 1, 2, 1, 1, 0, 1, 3, 1, 1, 0, 1, 4])
        frame = decode_img_frame(raw)
        self.assertEqual((frame.width, frame.height), (4, 2))
        self.assertEqual(frame.indices, bytes([0, 2, 3, 4, 0, 0, 0, 0]))
        self.assertEqual(frame.alpha, bytes([0, 255, 255, 255, 255, 0, 0, 0]))
        with self.assertRaises(ValueError):
            decode_img_frame(raw[:-1])
        with self.assertRaises(ValueError):
            decode_img_frame(raw + b'\0')

    def test_img_offsets_and_declared_size_are_checked(self):
        payload = bytes([1, 1, 0, 1, 7, 0])
        valid = struct.pack('<3I', 1, 0, len(payload)) + payload
        self.assertEqual(parse_img(valid), [payload])
        with self.assertRaises(ValueError):
            parse_img(valid[:-1])
        with self.assertRaises(ValueError):
            parse_img(struct.pack('<3I', 1, 1, len(payload)) + payload)
        with self.assertRaises(ValueError):
            parse_img(b'\xff' * 8)

if __name__ == '__main__':
    unittest.main()
