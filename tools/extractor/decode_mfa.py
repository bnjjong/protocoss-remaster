#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - BGM (*.MFA, 37곡) 바이너리 파서 및 메타데이터 추출기
"""
import os
import glob
import json
import struct

def parse_mfa_file(path):
    with open(path, 'rb') as f:
        file_size = os.path.getsize(path)
        magic = f.read(11)
        if magic != b'MUSIC FILE\x00':
            print(f"Skipping {path}: invalid magic {magic}")
            return None

        f1, f2, f3 = struct.unpack('BBB', f.read(3))
        f4 = struct.unpack('<I', f.read(4))[0]
        num_tracks = struct.unpack('<H', f.read(2))[0]

        tracks = []
        for t in range(num_tracks):
            t_raw = f.read(32)
            tracks.append({
                "track_id": t,
                "raw_bytes": list(t_raw),
                "hex": t_raw.hex()
            })

        data_size = struct.unpack('<I', f.read(4))[0]
        music_data = f.read(data_size)

        return {
            "filename": os.path.basename(path),
            "file_size": file_size,
            "f1": f1,
            "f2": f2,
            "f3": f3,
            "f4": f4,
            "num_tracks": num_tracks,
            "data_size": data_size,
            "tracks": tracks,
            "data_hex": music_data.hex()
        }

def main():
    snd_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game/S&W"
    out_dir = "/Users/df/github/protocoss-remaster/assets/audio/bgm"
    os.makedirs(out_dir, exist_ok=True)

    mfa_files = sorted(glob.glob(os.path.join(snd_dir, "*.MFA")))
    print(f"Found {len(mfa_files)} MFA files.")

    all_bgm = []
    for mfa in mfa_files:
        info = parse_mfa_file(mfa)
        if info:
            all_bgm.append(info)
            # Save individual JSON summary
            name = os.path.splitext(os.path.basename(mfa))[0].lower()
            with open(os.path.join(out_dir, f"{name}.json"), 'w', encoding='utf-8') as jf:
                json.dump(info, jf, indent=2)

    with open(os.path.join(out_dir, "bgm_catalog.json"), 'w', encoding='utf-8') as cat_f:
        json.dump({
            "total_count": len(all_bgm),
            "songs": [{
                "filename": b["filename"],
                "file_size": b["file_size"],
                "num_tracks": b["num_tracks"],
                "data_size": b["data_size"]
            } for b in all_bgm]
        }, cat_f, indent=2)

    print(f"Parsed and exported metadata for all {len(all_bgm)} BGM tracks!")

if __name__ == "__main__":
    main()
