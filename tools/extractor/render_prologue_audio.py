#!/usr/bin/env python3
"""원작 MFA 재생 루틴의 OPL 쓰기를 기록하고 프롤로그 배경음악을 렌더링한다."""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys

import numpy as np
import soundfile as sf
from unicorn.x86_const import UC_X86_REG_EAX
from record_original_prologue import PrologueMachine


class MusicMachine(PrologueMachine):
    def __init__(self, game_dir):
        super().__init__(game_dir)
        for address in (0x137b5, 0x13af8):
            self.hooks.pop(address)
        # 존재하는 FM 장치를 제공한다. 음표·악기·템포·반복 해석은 원작 코드로 실행한다.
        self.hooks[0x13f70] = self.noop
        self.hooks[0x128e2] = self.noop
        self.hooks[0x1292f] = self.noop
        self.writes = []
        self.fm_address = 0
        self.divisor = 65536
        self.pit_low = None

    def port_out(self, cpu, port, size, value, user):
        if port == 0x388:
            self.fm_address = value
        elif port == 0x389:
            self.writes.append((self.time, self.fm_address, value))
        elif port == 0x40:
            if self.pit_low is None:
                self.pit_low = value
            else:
                self.divisor = (self.pit_low | value << 8) or 65536
                self.pit_low = None


def render(game_dir, output_root, track=0):
    directory = output_root / ('assets/prologue' if track == 0 else f'assets/opening/music-{track:02}')
    directory.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((directory / 'manifest.json').read_text()) if track == 0 else {'trace': []}
    duration = manifest['duration_seconds'] if track == 0 else float('inf')
    machine = MusicMachine(game_dir)
    machine.call(0x13724, 0)
    machine.set_dword(0x5310, 1)
    music = (game_dir / f'S&W/S{track:04}.MFA').read_bytes()
    pointer = machine.allocate(len(music))
    machine.cpu.mem_write(pointer, music)
    machine.call(0x13af8, 127)
    machine.call(0x137b5, pointer)
    loop_end = []
    if track != 0:
        from unicorn import UC_HOOK_CODE
        machine.cpu.hook_add(UC_HOOK_CODE, lambda c,a,s,u: loop_end.append(machine.time),None,0x13a0e,0x13a0e)
    deadline = machine.dword(0x6b4) * machine.divisor / 1193182
    volumes = [(event['time'], event['volume']) for event in manifest['trace'] if 'volume' in event]
    index = ticks = 0
    while deadline < duration or index < len(volumes):
        next_volume = volumes[index][0] if index < len(volumes) else float('inf')
        if next_volume <= deadline:
            machine.time = next_volume
            machine.call(0x13af8, volumes[index][1])
            index += 1
        else:
            machine.time = deadline
            machine.call(0x13924)
            count = machine.reg(UC_X86_REG_EAX)
            if not 0 < count < 1_000_000:
                raise ValueError('원작 음악의 다음 이벤트 시간이 잘못되었습니다.')
            deadline += count * machine.divisor / 1193182
            ticks += 1
            if loop_end:
                duration = loop_end[0]
                break
        if ticks > 100000:
            raise ValueError('원작 음악의 재생 범위를 넘었습니다.')
    tools = Path(__file__).resolve().parent
    library = tools / ('build/opl.dylib' if sys.platform == 'darwin' else 'build/opl.so')
    library.parent.mkdir(exist_ok=True)
    subprocess.run(['clang','-O2','-shared','-fPIC',str(tools/'opl_render.c'),
                    str(tools/'vendor/nuked-opl3/opl3.c'),'-o',str(library)],check=True)
    opl = ctypes.CDLL(str(library))
    opl.protocoss_opl_new.argtypes = [ctypes.c_uint]
    opl.protocoss_opl_new.restype = ctypes.c_void_p
    opl.protocoss_opl_write.argtypes = [ctypes.c_void_p,ctypes.c_uint,ctypes.c_uint]
    opl.protocoss_opl_render.argtypes = [ctypes.c_void_p,ctypes.POINTER(ctypes.c_short),ctypes.c_uint]
    opl.protocoss_opl_free.argtypes = [ctypes.c_void_p]
    rate = 44100
    chip = opl.protocoss_opl_new(rate)
    if not chip: raise MemoryError('OPL 렌더러 메모리 확보 실패')
    position = 0
    total = round(duration * rate)
    digest = hashlib.sha256()
    peak = energy = 0
    output = directory / ('prologue.ogg' if track == 0 else 'music.ogg')
    try:
        with sf.SoundFile(output,mode='w',samplerate=rate,channels=2,format='OGG',subtype='VORBIS') as stream:
            def generate(target):
                nonlocal position, peak, energy
                while position < target:
                    count = min(4096, target - position)
                    buffer = (ctypes.c_short * (count * 2))()
                    opl.protocoss_opl_render(chip,buffer,count)
                    samples = np.ctypeslib.as_array(buffer).reshape(-1,2)
                    digest.update(bytes(buffer))
                    wide = samples.astype(np.int64)
                    peak = max(peak,int(np.abs(wide).max()))
                    energy += int((wide * wide).sum())
                    stream.write(samples)
                    position += count
            for time, register, value in machine.writes:
                generate(min(total, round(time * rate)))
                opl.protocoss_opl_write(chip,register,value)
            generate(total)
    finally:
        opl.protocoss_opl_free(chip)
    if energy == 0: raise ValueError('원작 음악의 렌더링 결과가 무음입니다.')
    report = {'source':f'S&W/S{track:04}.MFA','mode':'original OPL2 driver, rhythm enabled',
              'sample_rate':rate,'channels':2,'sample_frames':total,'duration_seconds':total/rate,
              'native_events':ticks,'opl_writes':len(machine.writes),'pcm_sha256':digest.hexdigest(),
              'peak':peak,'rms':(energy/(total*2))**0.5,
              'ogg_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),
              'opl_emulator':'Nuked-OPL3 765ec962e473aeb767e4cba74ffdc8f588ffbfe8'}
    (directory/'audio.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report),flush=True)
    return report

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir',type=Path,required=True)
    parser.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2])
    parser.add_argument('--track',type=int,default=0)
    args=parser.parse_args()
    render(args.game_dir,args.output_root,args.track)
