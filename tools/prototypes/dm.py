# Host prototypes (numpy) used to choose the demosaic, the RAW denoise and to test super-resolution before porting to Kotlin and GLSL.
# They read /tmp/ab/google.jpg, a sharp photograph used as ground truth (any sharp 4000x3000 JPEG works), and need numpy, Pillow and scipy.
# Run from this folder: python3 dm.py | rawden.py | sr.py
import numpy as np
from PIL import Image, ImageOps

def srgb2lin(a): a=a/255.0; return np.where(a<=0.04045,a/12.92,((a+0.055)/1.055)**2.4)
def lin2srgb(l): l=np.clip(l,0,1); return np.where(l<=0.0031308,12.92*l,1.055*l**(1/2.4)-0.055)*255

def mosaic(rgb):  # RGGB
    H,W,_=rgb.shape; m=np.zeros((H,W))
    m[0::2,0::2]=rgb[0::2,0::2,0]; m[0::2,1::2]=rgb[0::2,1::2,1]; m[1::2,0::2]=rgb[1::2,0::2,1]; m[1::2,1::2]=rgb[1::2,1::2,2]
    return m

def conv(a,taps):  # taps: list of (dx,dy,w); edge-replicated
    p=2; ap=np.pad(a,p,mode='edge'); H,W=a.shape; out=np.zeros_like(a)
    for dx,dy,w in taps: out+=w*ap[p+dy:p+dy+H,p+dx:p+dx+W]
    return out

# --- Malvar-He-Cutler as in IxRender
G_AT_RB=[(0,-2,-1),(0,-1,2),(-2,0,-1),(-1,0,2),(0,0,4),(1,0,2),(2,0,-1),(0,1,2),(0,2,-1)]
RB_SAMEROW=[(0,-2,.5),(-1,-1,-1),(1,-1,-1),(-2,0,-1),(-1,0,4),(0,0,5),(1,0,4),(2,0,-1),(-1,1,-1),(1,1,-1),(0,2,.5)]
RB_SAMECOL=[(-2,0,.5),(-1,-1,-1),(-1,1,-1),(0,-2,-1),(0,-1,4),(0,0,5),(0,1,4),(0,2,-1),(1,-1,-1),(1,1,-1),(2,0,.5)]
RB_BR=[(0,-2,-1.5),(-1,-1,2),(1,-1,2),(-2,0,-1.5),(0,0,6),(2,0,-1.5),(-1,1,2),(1,1,2),(0,2,-1.5)]
def mhc(m):
    H,W=m.shape; yy,xx=np.mgrid[0:H,0:W]; ry=(yy&1)==0; rx=(xx&1)==0
    isR=ry&rx; isB=(~ry)&(~rx); isG=~(isR|isB); redRow=isG&ry   # green on a red row
    g=np.where(isG,m,conv(m,[(a,b,w/8) for a,b,w in G_AT_RB]))
    rsr=conv(m,[(a,b,w/8) for a,b,w in RB_SAMEROW]); rsc=conv(m,[(a,b,w/8) for a,b,w in RB_SAMECOL]); rbr=conv(m,[(a,b,w/8) for a,b,w in RB_BR])
    r=np.where(isR,m,np.where(isB,rbr,np.where(redRow,rsr,rsc)))
    b=np.where(isB,m,np.where(isR,rbr,np.where(redRow,rsc,rsr)))
    return np.stack([r,g,b],-1)

def shift(a,dx,dy):
    p=3; ap=np.pad(a,p,mode='edge'); H,W=a.shape; return ap[p+dy:p+dy+H,p+dx:p+dx+W]

def directional(m,eps=1e-4,agg=True):
    """Hamilton-Adams green with gradient-weighted H/V blend, then colour-difference R/B with weighted interpolation."""
    H,W=m.shape; yy,xx=np.mgrid[0:H,0:W]; ry=(yy&1)==0; rx=(xx&1)==0
    isR=ry&rx; isB=(~ry)&(~rx); isG=~(isR|isB)
    c=m
    # green estimates at every site (used where not green)
    Gh=(shift(m,-1,0)+shift(m,1,0))/2+(2*c-shift(m,-2,0)-shift(m,2,0))/4
    Gv=(shift(m,0,-1)+shift(m,0,1))/2+(2*c-shift(m,0,-2)-shift(m,0,2))/4
    Dh=np.abs(shift(m,-1,0)-shift(m,1,0))+np.abs(2*c-shift(m,-2,0)-shift(m,2,0))
    Dv=np.abs(shift(m,0,-1)-shift(m,0,1))+np.abs(2*c-shift(m,0,-2)-shift(m,0,2))
    if agg:  # aggregate the gradient over the neighbouring same-type sites for a steadier decision
        k=[(-2,0,1),(0,0,2),(2,0,1),(0,-2,1),(0,2,1),(-2,-2,.5),(2,2,.5),(-2,2,.5),(2,-2,.5)]
        Dh=conv(Dh,[(a,b,w/8) for a,b,w in k]) ; Dv=conv(Dv,[(a,b,w/8) for a,b,w in k])
    wh=1/(eps+Dh)**2; wv=1/(eps+Dv)**2
    Gest=(wh*Gh+wv*Gv)/(wh+wv)
    g=np.where(isG,m,np.clip(Gest,0,None))
    # colour differences at R and B sites
    kr=np.where(isR,m-g,0.0); kb=np.where(isB,m-g,0.0)
    def interp_diff(k,site):
        # known at 'site' lattice (stride 2). fill G sites by averaging the 2 horizontal/vertical known neighbours (weighted by green gradient), other-colour sites by 4 diagonals (weighted)
        out=np.where(site,k,0.0)
        # horizontal / vertical neighbours at stride 1 are known only for G sites in the row/col of the colour
        kh=(shift(k,-1,0)+shift(k,1,0))/2; kv=(shift(k,0,-1)+shift(k,0,1))/2
        # diagonals: weights by inverse |G gradient| along each diagonal
        d1=np.abs(shift(g,-1,-1)-shift(g,1,1)); d2=np.abs(shift(g,1,-1)-shift(g,-1,1))
        k1=(shift(k,-1,-1)+shift(k,1,1))/2; k2=(shift(k,1,-1)+shift(k,-1,1))/2
        w1=1/(eps+d1)**2; w2=1/(eps+d2)**2; kd=(w1*k1+w2*k2)/(w1+w2)
        return kh,kv,kd,out
    kh,kv,kd,krk=interp_diff(kr,isR); r_k=np.where(isR,kr,np.where(isG&ry,kh,np.where(isG&~ry,kv,kd)))
    # on a green site in a red row, the red neighbours are left/right; in a blue row (green on blue row) they are above/below
    kh2,kv2,kd2,_=interp_diff(kb,isB); b_k=np.where(isB,kb,np.where(isG&~ry,kh2,np.where(isG&ry,kv2,kd2)))
    return np.stack([g+r_k,g,g+b_k],-1)

def psnr(a,b,crop=8):
    a=a[crop:-crop,crop:-crop]; b=b[crop:-crop,crop:-crop]; mse=((a-b)**2).mean(); return 10*np.log10(255**2/mse)

if __name__=='__main__':
    im=np.asarray(ImageOps.exif_transpose(Image.open('/tmp/ab/google.jpg')).convert('RGB'),dtype=np.float64)
    boxes={'label':(1450,880,2250,1480),'rug':(100,1000,900,1600),'floor':(2600,1300,3400,1900),'figure':(1500,150,2300,750),'gnomes':(500,0,1300,600)}
    tot={'mhc':[],'dir':[]}
    for name,(x0,y0,x1,y1) in boxes.items():
        gt=im[y0:y1,x0:x1]; lin=srgb2lin(gt); m=mosaic(lin)
        for tag,fn in (('mhc',mhc),('dir',directional)):
            out=lin2srgb(fn(m)); p=psnr(out,gt); tot[tag].append(p)
        print('%-7s MHC %.2f dB   directional %.2f dB'%(name,tot['mhc'][-1],tot['dir'][-1]))
    print('mean   MHC %.2f   directional %.2f'%(np.mean(tot['mhc']),np.mean(tot['dir'])))
