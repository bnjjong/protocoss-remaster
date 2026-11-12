"""AREA*.G&M의 원본 바이트와 진입 분기를 보존한다. 이벤트 본문은 실행하지 않는다."""
from dataclasses import dataclass
import struct


@dataclass(frozen=True)
class EventGroup:
    offset:int
    routes:tuple
    fallback:int
    fallback_instruction:int


class EventArchive:
    def __init__(self, raw):
        if len(raw)<6:raise ValueError('이벤트 파일 헤더가 잘렸습니다.')
        length,count=struct.unpack_from('<IH',raw)
        if length!=len(raw)-6:raise ValueError('이벤트 파일 길이가 헤더와 다릅니다.')
        start=6+count*2
        if not count or start>=len(raw):raise ValueError('이벤트 그룹 목록이 잘못되었습니다.')
        self.offsets=struct.unpack_from(f'<{count}H',raw,6)
        self.code=bytes(value^127 for value in raw[start:])
        if len(self.code)>65536:raise ValueError('16비트 이벤트 주소 범위를 넘었습니다.')
        groups=[]
        for offset in self.offsets:
            p=offset;routes=[]
            self.check(p,1)
            while self.code[p]==0xf0:
                self.check(p,7)
                variable,argument,destination=struct.unpack_from('<3H',self.code,p+1)
                if variable!=1:raise ValueError('분석한 이벤트 인자 분기 형식과 다릅니다.')
                self.check(destination,1);routes.append((argument,destination));p+=7
                self.check(p,1)
            self.check(p,3)
            if self.code[p]!=0xfa:raise ValueError('이벤트 기본 분기의 점프 명령이 다릅니다.')
            fallback=struct.unpack_from('<H',self.code,p+1)[0];self.check(fallback,1)
            groups.append(EventGroup(offset,tuple(routes),fallback,p))
        self.groups=tuple(groups)

    def check(self, offset, length):
        if offset<0 or offset+length>len(self.code):raise ValueError('이벤트 주소가 파일 범위를 벗어났습니다.')

    def resolve(self, group, argument):
        if not 0<=group<len(self.groups) or not 0<=argument<=65535:raise ValueError('이벤트 그룹·인자 범위 오류')
        entry=self.groups[group]
        return next((destination for value,destination in entry.routes if value==argument),entry.fallback)
