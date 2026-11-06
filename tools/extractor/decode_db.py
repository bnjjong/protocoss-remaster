#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - RPG 수치 데이터베이스 (*.LST) JSON 변환기
"""
import os
import json
import struct

def parse_string(raw_bytes):
    null_pos = raw_bytes.find(b'\x00')
    target = raw_bytes[:null_pos] if null_pos != -1 else raw_bytes
    return target.decode('cp1361', errors='replace').strip()

def decode_item_lst(path):
    rec_len = 58
    with open(path, 'rb') as f:
        data = f.read()
    items = []
    for i in range(len(data) // rec_len):
        rec = data[i*rec_len : (i+1)*rec_len]
        name = parse_string(rec[:16])
        # Unpack remaining fields as raw bytes & integers
        raw_ints = [struct.unpack('<H', rec[j:j+2])[0] for j in range(16, rec_len - (rec_len % 2), 2)]
        items.append({
            "id": i,
            "name": name,
            "raw_values": raw_ints,
            "raw_hex": rec.hex()
        })
    return items

def decode_magic_lst(path):
    rec_len = 89
    with open(path, 'rb') as f:
        data = f.read()
    magics = []
    for i in range(len(data) // rec_len):
        rec = data[i*rec_len : (i+1)*rec_len]
        name = parse_string(rec[:16])
        raw_ints = [struct.unpack('<H', rec[j:j+2])[0] for j in range(16, rec_len - (rec_len % 2), 2)]
        magics.append({
            "id": i,
            "name": name,
            "raw_values": raw_ints,
            "raw_hex": rec.hex()
        })
    return magics

def decode_monster_lst(path):
    rec_len = 89
    with open(path, 'rb') as f:
        data = f.read()
    monsters = []
    for i in range(len(data) // rec_len):
        rec = data[i*rec_len : (i+1)*rec_len]
        name = parse_string(rec[:16])
        raw_ints = [struct.unpack('<H', rec[j:j+2])[0] for j in range(16, rec_len - (rec_len % 2), 2)]
        monsters.append({
            "id": i,
            "name": name,
            "raw_values": raw_ints,
            "raw_hex": rec.hex()
        })
    return monsters

def decode_weapon_lst(path):
    rec_len = 84
    with open(path, 'rb') as f:
        data = f.read()
    weapons = []
    for i in range(len(data) // rec_len):
        rec = data[i*rec_len : (i+1)*rec_len]
        name = parse_string(rec[:16])
        raw_ints = [struct.unpack('<H', rec[j:j+2])[0] for j in range(16, rec_len - (rec_len % 2), 2)]
        weapons.append({
            "id": i,
            "name": name,
            "raw_values": raw_ints,
            "raw_hex": rec.hex()
        })
    return weapons

def main():
    data_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game/DATA"
    out_dir = "/Users/df/github/protocoss-remaster/assets/data/db"
    os.makedirs(out_dir, exist_ok=True)

    parsers = [
        ("ITEM.LST", "items.json", decode_item_lst),
        ("MAGIC.LST", "magics.json", decode_magic_lst),
        ("MON_PARA.LST", "monsters.json", decode_monster_lst),
        ("WEAPON.LST", "weapons.json", decode_weapon_lst),
    ]

    for fname, out_name, parser in parsers:
        path = os.path.join(data_dir, fname)
        if os.path.exists(path):
            result = parser(path)
            out_path = os.path.join(out_dir, out_name)
            with open(out_path, 'w', encoding='utf-8') as out_f:
                json.dump(result, out_f, ensure_ascii=False, indent=2)
            print(f"Exported {len(result)} records from {fname} -> {out_name}")

if __name__ == "__main__":
    main()
