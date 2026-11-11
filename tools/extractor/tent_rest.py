"""원작 텐트의 조건·회복·팔레트/음량 순서·휴식 그림을 실행해 기록한다."""
import argparse,base64,hashlib,json,struct
from pathlib import Path
from PIL import Image
from unicorn import UC_HOOK_CODE
from unicorn.x86_const import UC_X86_REG_EAX,UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX,UC_X86_REG_ESP
from equipment_reference import EquipmentReference
from party_equipment import changes
from decode_title import decode_img_frame
from verify_original_user_sprite import OriginalSpriteRenderer
from verify_original_map import unpack_game

class TentReference(EquipmentReference):
    def rest(self,key=28):
        trace=[];wait=[]
        self.byte(0x1660,1);self.dword(0x1644,127);self.dword(0x164c,0x3e1000)
        self.cpu.mem_write(0x7e610,struct.pack('<HHH',37,0,0))
        def hook(c,a,size,user):
            value=c.reg_read(UC_X86_REG_EAX)
            if a==0x2b838:
                trace.append(dict(kind='palette',level=value,total=c.reg_read(UC_X86_REG_EDX)));self.return_from(c)
            elif a==0x66368:trace.append(dict(kind='volume',level=value));self.return_from(c)
            elif a==0x2b650:
                name=bytes(c.mem_read(value,256)).split(b'\0')[0].decode('ascii');trace.append(dict(kind='music_load',name=name));c.reg_write(UC_X86_REG_EAX,0x3e1000);self.return_from(c)
            elif a in (0x66025,0x66f6e):trace.append(dict(kind='music_start' if a==0x66025 else 'music_free'));self.return_from(c)
            elif a==0x1724c:
                wait.append(base64.b64encode(self.saved()).decode());trace.append(dict(kind='key_wait'));self.dword(0x1520,1)
        token=self.cpu.hook_add(UC_HOOK_CODE,hook)
        try:events=self.invoke(0x173b4,keys=(key,1))
        finally:self.cpu.hook_del(token)
        return events,trace,wait


def palette_levels(ref):
    # 원작 0x2B838을 실행해 6비트 색 강도 0~63의 실제 변환표를 얻는다.
    ref.cpu.mem_write(0x7fa40,bytes(v for i in range(256) for v in (i%64,)*3))
    result=[]
    for level in range(65):
        values=[]
        def hook(c,a,size,user):
            if a==0x2ca33:ref.return_from(c)
            elif a==0x2d34c:
                r,g,b=[c.reg_read(reg) for reg in (UC_X86_REG_EDX,UC_X86_REG_EBX,UC_X86_REG_ECX)]
                assert r==g==b;values.append(r);ref.return_from(c)
        token=ref.cpu.hook_add(UC_HOOK_CODE,hook)
        try:ref.invoke(0x2b838,level,64)
        finally:ref.cpu.hook_del(token)
        assert len(values)==256 and all(values[i]==values[i%64] for i in range(256))
        result.append(values[:64])
    return result

def verify(game_dir,output_root,executable=None):
    exe=executable if executable is not None else unpack_game(game_dir)
    ref=TentReference(exe);start=(game_dir/'DATA/START.DAT').read_bytes();fixtures=[];cases=[];success_trace=None
    groups=[[0],[1],[10],[0,1,2,3,4],[0,3,5,7,10],[2,4,6,8,9]]
    maxima=[0,1,2,3,4,5,9,10,99,255,999,65535]
    for party in groups:
        for variant in range(12):
            raw=bytearray(start);raw[123:162]=bytes(39);raw[160]=2
            for character in range(11):
                off=character*153;raw[347+off]=int(character in party)
                maximum=maxima[(variant+character)%len(maxima)];target=int(maximum*.8)
                current=[0,max(0,target-1),target,min(65535,target+1),maximum,min(65535,maximum+1)][variant%6]
                struct.pack_into('<4H',raw,352+off,current,maximum,max(0,target-1),maximum)
                raw[448+off:460+off]=bytes([(variant*21+character)%256])*12
                raw[460+off:472+off]=bytes([0,1,4,5,9,10,99,100,199,200,254,255])
                raw[472+off]=(variant+character)%6;raw[477+off]=157;raw[478+off]=174;raw[387+off]=12
            for i in range(7):
                raw[2020+i*46]=int((i+variant)%2==0);struct.pack_into('<HH',raw,2058+i*46,variant,99+i)
            struct.pack_into('<H',raw,2350,[10,24,25][variant%3]);struct.pack_into('<H',raw,2936,2)
            struct.pack_into('<H',raw,2540,4321)
            fixture=len(fixtures);fixtures.append(base64.b64encode(raw).decode())
            ref.prepare(game_dir,raw)
            for permission,count in [(1,1),(1,2),(1,255),(0,2),(2,2),(65535,2),(1,0)]:
                before=bytearray(raw);before[160]=count;struct.pack_into('<H',before,2346,permission)
                ref.load_save(before);assert ref.saved()==before
                events,trace,wait=ref.rest()
                after=ref.saved();allowed=permission==1 and count>0
                assert bool(wait)==allowed
                if allowed and success_trace is None:success_trace=trace
                cases.append(dict(fixture=fixture,permission=permission,count=count,after=changes(before,after),
                    at_wait=changes(before,base64.b64decode(wait[0])) if wait else [],step=ref.word(0xe510),
                    effects=[e['id'] for e in events if e['kind']=='effect'],music=[e['name'] for e in trace if e['kind']=='music_load']))
    # 원작 키 대기 함수를 통과하지 않는 취소는 수량과 상태를 바꾸지 않는다.
    raw=bytearray(start);raw[160]=2;struct.pack_into('<H',raw,2346,1)
    ref.prepare(game_dir,raw);events,trace,_=ref.rest(1)
    assert ref.saved()==raw and not trace
    frame=decode_img_frame(ref.raw['imenu-289']);palette=(game_dir/'DATA/PROTOCOS.PAL').read_bytes()
    native=OriginalSpriteRenderer(exe).render(struct.pack('<HH',frame.width,frame.height)+ref.raw['imenu-289'],palette)
    assert frame.to_image(palette).tobytes()==native.tobytes()
    image=Image.new('RGBA',(320,200),(0,0,0,255));image.alpha_composite(native,(88,48))
    directory=output_root/'assets/tent';directory.mkdir(parents=True,exist_ok=True);image.save(directory/'rest.png')
    result=dict(sources={name:hashlib.sha256((game_dir/name).read_bytes()).hexdigest() for name in ('AMOS.EXE','PROTOCOS.FNT','DATA/START.DAT','GRP/IMENU.IMG','DATA/PROTOCOS.PAL','S&W/SLEEP.MFA','PROLOGUE.EXE')},
        fixtures=fixtures,cases=cases,trace=success_trace,dac_levels=palette_levels(ref),rest_image=dict(frame=289,x=88,y=48,width=frame.width,height=frame.height,pixels=frame.width*frame.height))
    (output_root/'docs/tent-native.json').write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
    # 허용 상태는 원작 이벤트 변수로 판단하며 지형에서 추측하지 않는다.
    (directory/'rest.json').write_text(json.dumps(dict(image='rest.png',music='music.ogg',permission_variable=2,permission_value=1,item=37,palette_steps=65,step_hz=70,wait='fresh-key',music_loop=True,source_image=result['rest_image']),ensure_ascii=False,indent=2)+'\n')
    print('텐트 원작 검사:',len(cases),'표본, 그림',frame.width*frame.height,'픽셀',flush=True)
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--game-dir',type=Path,required=True)
    p.add_argument('--output-root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();verify(a.game_dir,a.output_root)
