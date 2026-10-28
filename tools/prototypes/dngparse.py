import struct, numpy as np
def parse(p):
    b=open(p,'rb').read()
    bo='<' if b[:2]==b'II' else '>'
    ifd=struct.unpack(bo+'I',b[4:8])[0]
    def read_ifd(off):
        n=struct.unpack(bo+'H',b[off:off+2])[0]; tags={}
        for i in range(n):
            e=b[off+2+i*12:off+14+i*12]; tag,typ,cnt=struct.unpack(bo+'HHI',e[:8])
            sz={1:1,2:1,3:2,4:4,5:8,7:1,8:2,9:4,10:8,11:4,12:8,16:8}[typ]
            data=e[8:12] if sz*cnt<=4 else b[struct.unpack(bo+'I',e[8:12])[0]:][:sz*cnt]
            fmt={1:'B',3:'H',4:'I',8:'h',9:'i',16:'Q'}.get(typ)
            tags[tag]=(typ,cnt,struct.unpack(bo+fmt*cnt,data[:sz*cnt]) if fmt else data)
        return tags
    tags=read_ifd(ifd); cands=[tags]
    if 330 in tags:
        for o in tags[330][2]: cands.append(read_ifd(o))
    for t in cands:
        if 273 in t and t.get(259,(0,0,(1,)))[2][0]==1 and t[256][2][0]>2000:
            w=t[256][2][0]; h=t[257][2][0]; off=t[273][2][0]
            return np.frombuffer(b[off:off+w*h*2],dtype=bo+'u2').reshape(h,w)
    raise SystemExit('no raw')
