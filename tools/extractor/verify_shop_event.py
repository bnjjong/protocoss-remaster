#!/usr/bin/env python3
"""원작 상점 명령의 인자·대화 분기·거래 후 이벤트 복귀·저장을 대조한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_EDX
from verify_original_map import unpack_game,EXPECTED_HASHES
from verify_event_rewards import RewardReference
from shop_trading import ShopReference

def parser(exe,args):
    ref=RewardReference(exe);c=ref.cpu;c.mem_write(0x240000,struct.pack('<HH',*args));ref.dword(0x15a4,0x240000);captured=[]
    def hook(cpu,at,size,user):
        if at==0x29a1c:
            captured.append([cpu.reg_read(r) for r in (UC_X86_REG_EAX,UC_X86_REG_EDX)]);ref.return_from(cpu)
    token=c.hook_add(UC_HOOK_CODE,hook)
    try:ref.call(0x25fe8)
    finally:c.hook_del(token)
    return dict(input=args,args=captured[0],advanced=struct.unpack('<I',c.mem_read(0x715a4,4))[0]-0x240000)

def synthetic():
    code=b'\xfa\x03\0\x10\0\0\0\0\xf2'+struct.pack('<HH',99,456)+b'\xf1'
    return struct.pack('<IHH',len(code)+2,1,0)+bytes(b^127 for b in code)

def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    raw=(game_dir/'DATA/AREA00.G&M').read_bytes();start=(game_dir/'DATA/START.DAT').read_bytes()
    ref=RewardReference(exe);trade=ShopReference(exe);cases=[]
    def source(original,stage,group=4):
        b=bytearray(original);struct.pack_into('<5I',b,96,5,0,0,144,160)
        for i,v in {0:group,1:77,4:10,8:stage,99:123,299:54321}.items():struct.pack_into('<H',b,2342+i*2,v)
        return b
    for stage in (0,1,2,3,4,5,65535):
        b=source(start,stage)
        result=ref.event_reward(game_dir,raw,b,2,shop=lambda save,table,kind:save)
        cases.append(dict(name=f'stage-{stage}',stage=stage,argument=2,before=base64.b64encode(b).decode(),**result))
    vectors=json.loads((output_root/'docs/shop-native.json').read_text())['cases']
    for row in vectors:
        b=source(base64.b64decode(row['before']),4)
        def transact(save,table,kind):
            if (table,kind)!=(0,0):raise ValueError('연결하지 않은 가격표')
            trade.prepare(game_dir,save);trade.quantity(row['item'],row['price'],row['sell'],row['keys']);return trade.saved()
        result=ref.event_reward(game_dir,raw,b,2,shop=transact)
        cases.append(dict(name=row['name'],trade=row['name'],stage=4,argument=2,before=base64.b64encode(b).decode(),**result))
    b=source(start,4,0);program=synthetic()
    result=ref.event_reward(game_dir,program,b,7,shop=lambda save,table,kind:save)
    cases.append(dict(name='resume-next-command',argument=7,raw=base64.b64encode(program).decode(),before=base64.b64encode(b).decode(),**result))
    report=dict(executable_sources=EXPECTED_HASHES,sources={p:hashlib.sha256((game_dir/p).read_bytes()).hexdigest() for p in ('DATA/AREA00.G&M','DATA/ITEM_P.GUP')},cases=cases,
        parser=[parser(exe,a) for a in [(0,0),(1,1),(32768,65535),(65535,32768)]],
        stubs='원작 VM·0x25FE8의 인자 읽기·대화 분기·보상·상점 이후 명령을 실행한다. 0x29A1C의 화면 루프는 생략한다. 거래 193개는 별도 CPU에서 원작 수량 함수 0x27EE4/0x28CF8을 실행한 저장 결과를 복사하여 VM의 호출 스택을 보존하고 복귀한다. 거래 화면의 그리기·음량·입력은 기존 shop-native/shop-render와 실제 앱 검사로 대조한다. 압축 해제 실행 파일과 메모리는 RAM에만 보존한다.')
    (output_root/'docs/restoration/shop-event-native.json').write_text(json.dumps(report,ensure_ascii=False,separators=(',',':'))+'\n')
    print('상점 원작:',len(cases),'상태, 거래',len(vectors),flush=True);return report

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True);p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
