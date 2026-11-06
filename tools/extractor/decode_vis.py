#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - VIS 컷씬/비주얼 전수 디코더
"""
import os
import glob
import struct
from PIL import Image

def decode_vis_file(vis_path, out_dir):
    basename = os.path.splitext(os.path.basename(vis_path))[0]
    
    with open(vis_path, 'rb') as f:
        file_size = os.path.getsize(vis_path)
        if file_size < 775:
            print(f"Skipping {vis_path}: too small ({file_size} bytes)")
            return 0

        header_bytes = f.read(4)
        if len(header_bytes) < 4:
            return 0
        fc, meta = struct.unpack('<HH', header_bytes)
        
        magic = f.read(3)
        if magic != b'VIS':
            # Check if magic is at offset 4
            f.seek(0)
            data = f.read(16)
            vis_pos = data.find(b'VIS')
            if vis_pos == -1:
                print(f"Skipping {vis_path}: not a VIS file (magic: {magic})")
                return 0
            f.seek(vis_pos - 4)
            fc, meta = struct.unpack('<HH', f.read(4))
            f.read(3) # 'VIS'

        pal_raw = f.read(768)
        if len(pal_raw) < 768:
            return 0

        palette = []
        for i in range(256):
            palette.extend([
                min(255, pal_raw[i*3] * 4),
                min(255, pal_raw[i*3+1] * 4),
                min(255, pal_raw[i*3+2] * 4)
            ])

        if fc == 0 or fc > 1000:
            print(f"Invalid frame count in {vis_path}: {fc}")
            return 0

        t1 = [struct.unpack('<HHH', f.read(6)) for _ in range(fc)]
        t2 = [struct.unpack('<I', f.read(4))[0] for _ in range(fc)]

        saved_count = 0
        for frame_idx in range(fc):
            offset = t2[frame_idx]
            if offset >= file_size:
                continue

            f.seek(offset)
            hdr = f.read(6)
            if len(hdr) < 6:
                continue
            v1, v2, v3 = struct.unpack('<HHH', hdr)
            
            block_sizes = []
            for _ in range(v2):
                bsz_data = f.read(2)
                if len(bsz_data) < 2:
                    break
                block_sizes.append(struct.unpack('<H', bsz_data)[0])

            screen = [0] * (320 * 200)

            for b_sz in block_sizes:
                block_data = f.read(b_sz)
                if len(block_data) < 4:
                    continue
                x_init, y_init = struct.unpack('<HH', block_data[:4])
                pos = 4
                cur_ptr = y_init * 320 + x_init

                while pos < len(block_data):
                    b = block_data[pos]
                    pos += 1
                    if b == 0x80:
                        if pos + 2 > len(block_data):
                            break
                        val16 = struct.unpack('<H', block_data[pos:pos+2])[0]
                        pos += 2
                        if val16 == 0:
                            break
                        elif val16 & 0x8000:
                            if val16 & 0x4000:
                                count = val16 & 0x3FFF
                                color = block_data[pos] if pos < len(block_data) else 0
                                pos += 1
                                for _ in range(count):
                                    if cur_ptr < len(screen):
                                        screen[cur_ptr] = color
                                    cur_ptr += 1
                            else:
                                count = val16 & 0x3FFF
                                for _ in range(count):
                                    if cur_ptr < len(screen):
                                        screen[cur_ptr] = block_data[pos] if pos < len(block_data) else 0
                                    pos += 1
                                    cur_ptr += 1
                        else:
                            skip = val16 & 0x7FFF
                            cur_ptr += skip
                    elif b == 0:
                        if pos + 2 > len(block_data):
                            break
                        count = block_data[pos]
                        color = block_data[pos+1]
                        pos += 2
                        for _ in range(count):
                            if cur_ptr < len(screen):
                                screen[cur_ptr] = color
                            cur_ptr += 1
                    elif b & 0x80:
                        skip = b & 0x7F
                        cur_ptr += skip
                    else:
                        count = b & 0x7F
                        for _ in range(count):
                            if cur_ptr < len(screen):
                                screen[cur_ptr] = block_data[pos] if pos < len(block_data) else 0
                            pos += 1
                            cur_ptr += 1

            img = Image.frombytes('P', (320, 200), bytes(screen))
            img.putpalette(palette)
            
            # Save as PNG
            out_filename = f"{basename}_{frame_idx:02d}.png" if fc > 1 else f"{basename}.png"
            out_path = os.path.join(out_dir, out_filename)
            img.save(out_path)
            saved_count += 1

        return saved_count

def main():
    grp_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game/GRP"
    out_dir = "/Users/df/github/protocoss-remaster/assets/cutscenes"
    os.makedirs(out_dir, exist_ok=True)

    vis_files = sorted(glob.glob(os.path.join(grp_dir, "*.VIS")))
    print(f"Found {len(vis_files)} VIS files.")

    total_extracted = 0
    for vis in vis_files:
        count = decode_vis_file(vis, out_dir)
        total_extracted += count

    print(f"Extracted {total_extracted} cutscene/visual frames from {len(vis_files)} VIS files.")

if __name__ == "__main__":
    main()
