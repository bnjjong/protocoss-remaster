#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - 대사 및 이벤트 스크립트 (AREA*.G&M) 복호화 및 UTF-8 JSON 변환기
"""
import os
import glob
import json
import re

def parse_dialogue_block(raw_text):
    dialogues = []
    # Pattern to match: /[<ID><Portrait>Name]Text/E or /D
    pattern = re.compile(r'/\[([^\]]+)\](.*?)(?=/\[|$|/E)')
    for match in pattern.finditer(raw_text):
        tag = match.group(1).strip()
        body = match.group(2).strip()
        
        # Clean tags like /N (newline), /D (page break), /E (dialogue end)
        clean_text = body.replace('/N', '\n').replace('/D', '\n--- (다음) ---\n').replace('/E', '')
        dialogues.append({
            "speaker_tag": tag,
            "text": clean_text
        })
    return dialogues

def decode_gm_file(path):
    with open(path, 'rb') as f:
        raw = f.read()

    decrypted = bytes([b ^ 0x7F for b in raw])
    full_text = decrypted.decode('cp1361', errors='replace')

    # Split into sections or dialogue segments by /E (end of dialogue)
    raw_segments = full_text.split('/E')
    segments = []
    for idx, seg in enumerate(raw_segments):
        if '/[' in seg:
            parsed = parse_dialogue_block(seg + '/E')
            if parsed:
                segments.append({
                    "segment_id": idx,
                    "dialogues": parsed,
                    "raw_script": seg.strip()
                })

    return {
        "file": os.path.basename(path),
        "total_segments": len(segments),
        "segments": segments
    }

def main():
    data_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game/DATA"
    out_dir = "/Users/df/github/protocoss-remaster/assets/data/scripts"
    os.makedirs(out_dir, exist_ok=True)

    gm_files = sorted(glob.glob(os.path.join(data_dir, "AREA*.G&M")))
    print(f"Found {len(gm_files)} script files.")

    for gm in gm_files:
        name = os.path.splitext(os.path.basename(gm))[0]
        parsed = decode_gm_file(gm)
        out_json = os.path.join(out_dir, f"{name.lower()}_scripts.json")
        with open(out_json, 'w', encoding='utf-8') as f:
            json.dump(parsed, f, ensure_ascii=False, indent=2)
        print(f"Decoded {name} -> {parsed['total_segments']} dialogue segments.")

if __name__ == "__main__":
    main()
