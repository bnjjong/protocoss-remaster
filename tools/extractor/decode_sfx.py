#!/usr/bin/env python3
"""
프로토코스: 신들의 예언서 - 효과음 (*.EFF, 34종) 16비트 WAV 일괄 변환기
"""
import os
import glob
import struct
import wave

def convert_eff_to_wav(eff_path, out_path, sample_rate=11025):
    with open(eff_path, 'rb') as f:
        length_bytes = f.read(4)
        if len(length_bytes) < 4:
            return False
        data_len = struct.unpack('<I', length_bytes)[0]
        raw_pcm = f.read(data_len)

    # 8-bit unsigned PCM (0~255, center 128) -> 16-bit signed PCM (-32768~32767)
    pcm_16bit = bytearray()
    for b in raw_pcm:
        val16 = (int(b) - 128) * 256
        val16 = max(-32768, min(32767, val16))
        pcm_16bit.extend(struct.pack('<h', val16))

    with wave.open(out_path, 'wb') as wav:
        wav.setnchannels(1)      # Mono
        wav.setsampwidth(2)      # 16-bit
        wav.setframerate(sample_rate)
        wav.writeframes(pcm_16bit)

    return True

def main():
    snd_dir = "/Users/df/Downloads/Protocoss/Protocoss_Game/S&W"
    out_dir = "/Users/df/github/protocoss-remaster/assets/audio/sfx"
    os.makedirs(out_dir, exist_ok=True)

    eff_files = sorted(glob.glob(os.path.join(snd_dir, "*.EFF")))
    print(f"Found {len(eff_files)} EFF sound effect files.")

    converted = 0
    for eff in eff_files:
        name = os.path.splitext(os.path.basename(eff))[0].lower()
        out_wav = os.path.join(out_dir, f"{name}.wav")
        if convert_eff_to_wav(eff, out_wav):
            converted += 1

    print(f"Successfully converted {converted} / {len(eff_files)} sound effects to 16-bit WAV!")

if __name__ == "__main__":
    main()
