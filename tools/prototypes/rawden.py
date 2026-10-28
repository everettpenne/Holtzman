import numpy as np, sys
sys.path.insert(0,__import__("os").path.dirname(__file__))
from dm import *
def cellsum(m):  # 2x2 cell gray (sum), shape (H/2,W/2)
    return m[0::2,0::2]+m[0::2,1::2]+m[1::2,0::2]+m[1::2,1::2]
def raw_denoise(m,shot,read2,neff,h=1.0,R=2):
    """m in counts above black. Bilateral on the cell grid, same-colour samples averaged (no colour mixing)."""
    H,W=m.shape; ch,cw=H//2,W//2
    g=cellsum(m)                                   # guide: black-subtracted cell sum
    var=(shot*np.maximum(g,0)+4*read2)/neff        # variance of the cell sum after the merge
    out=np.zeros_like(m); den=np.zeros((ch,cw))
    num=[np.zeros((ch,cw)) for _ in range(4)]
    gp=np.pad(g,R,mode='edge'); varp=np.pad(var,R,mode='edge')
    sub=[m[0::2,0::2],m[0::2,1::2],m[1::2,0::2],m[1::2,1::2]]
    subp=[np.pad(a,R,mode='edge') for a in sub]
    for dy in range(-R,R+1):
        for dx in range(-R,R+1):
            q=gp[R+dy:R+dy+ch,R+dx:R+dx+cw]
            d=q-g
            w=np.exp(-(d*d)/(2*h*h*2*var+1e-9))*np.exp(-(dx*dx+dy*dy)/(2*1.5**2))
            den+=w
            for k in range(4): num[k]+=w*subp[k][R+dy:R+dy+ch,R+dx:R+dx+cw]
    o=np.zeros_like(m)
    o[0::2,0::2]=num[0]/den; o[0::2,1::2]=num[1]/den; o[1::2,0::2]=num[2]/den; o[1::2,1::2]=num[3]/den
    return o
if __name__=='__main__':
    im=np.asarray(ImageOps.exif_transpose(Image.open('/tmp/ab/google.jpg')).convert('RGB'),dtype=np.float64)
    boxes={'label':(1450,880,2250,1480),'rug':(100,1000,900,1600),'figure':(1500,150,2300,750),'floor':(2600,1300,3400,1900)}
    rng=np.random.default_rng(3)
    # scene brightness: scale so mean linear ~ 0.12 of a 1023-64 range, like our dim scene
    white=959.0
    for iso,shot,read2,neff in ((100,0.17,1.2,6),(800,1.36,4.5,6),(3200,5.4,50.0,6)):
        for h in (0.0,0.7,1.0,1.4):
            ps=[]
            for name,(x0,y0,x1,y1) in boxes.items():
                gt=srgb2lin(im[y0:y1,x0:x1]); lin=gt*0.5   # counts fraction
                counts=lin*white
                m=mosaic(counts)
                noisy=m+rng.normal(0,1,m.shape)*np.sqrt((shot*np.maximum(m,0)+read2)/neff)
                den=noisy if h==0 else raw_denoise(noisy,shot,read2,neff,h)
                out=lin2srgb(directional(den/white*2.0,eps=0.02)); ps.append(psnr(out,im[y0:y1,x0:x1]))
            print('ISO %4d h=%.1f  PSNR %.2f dB'%(iso,h,np.mean(ps)))
