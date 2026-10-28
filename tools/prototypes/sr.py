import numpy as np, sys
sys.path.insert(0,__import__("os").path.dirname(__file__))
from dm import *
from scipy.ndimage import shift as ndshift, zoom as ndzoom

def box_down(a,k=2): H,W=a.shape[:2]; return a[:H//k*k,:W//k*k].reshape(H//k,k,W//k,k,*a.shape[2:]).mean(axis=(1,3))

def make_frames(hr_lin, shifts, noise_var_fn, rng):
    """hr_lin: HxWx3 linear 'true scene' at 2x sensor resolution. A sensor frame with shift (sx,sy) in sensor px (multiples of 0.5)."""
    frames=[]
    for sx,sy in shifts:
        dx=int(round(sx*2)); dy=int(round(sy*2))
        sh=np.roll(hr_lin,(-dy,-dx),axis=(0,1))        # scene moves by (sx,sy) sensor px relative to the sensor
        sens=box_down(sh,2)                            # each sensor pixel integrates a 2x2 block of the hi-res scene
        m=mosaic(sens); m=m+rng.normal(0,1,m.shape)*np.sqrt(noise_var_fn(np.maximum(m,0)))
        frames.append(m)
    return frames

def superres(frames,shifts,sigma=0.55,S=2,noise_floor=None):
    """Splat every raw sample, at its true position (sensor px, after undoing the frame's shift), into per-colour accumulators at S x resolution."""
    H,W=frames[0].shape; oh,ow=H*S,W*S
    acc=np.zeros((3,oh,ow)); ws=np.zeros((3,oh,ow))
    yy,xx=np.mgrid[0:H,0:W]; ch=np.zeros((H,W),int); ch[(yy&1)==0]=1; ch[((yy&1)==0)&((xx&1)==0)]=0; ch[((yy&1)==1)&((xx&1)==1)]=2
    for m,(sx,sy) in zip(frames,shifts):
        # a sample at sensor pixel (x,y) centre sees the scene point (x+0.5+sx, y+0.5+sy) in reference coordinates
        px=(xx+0.5+sx)*S-0.5; py=(yy+0.5+sy)*S-0.5      # position in output pixel units (pixel centres at integers)
        x0=np.floor(px).astype(int); y0=np.floor(py).astype(int)
        for oy in (-1,0,1,2):
            for ox in (-1,0,1,2):
                ux=x0+ox; uy=y0+oy
                d2=((ux-px)**2+(uy-py)**2)/(S*S)           # distance in sensor px
                w=np.exp(-d2/(2*sigma**2))
                ok=(ux>=0)&(ux<ow)&(uy>=0)&(uy<oh)
                for c in range(3):
                    sel=ok&(ch==c)
                    np.add.at(acc[c],(uy[sel],ux[sel]),w[sel]*m[sel]); np.add.at(ws[c],(uy[sel],ux[sel]),w[sel])
    out=np.where(ws>1e-3,acc/np.maximum(ws,1e-9),np.nan)
    return np.moveaxis(out,0,-1),np.moveaxis(ws,0,-1)

def fill(out,fallback):
    return np.where(np.isnan(out),fallback,out)

if __name__=='__main__':
    im=np.asarray(ImageOps.exif_transpose(Image.open('/tmp/ab/google.jpg')).convert('RGB'),dtype=np.float64)
    rng=np.random.default_rng(5)
    boxes={'label':(1450,880,1850,1180),'figure':(1500,150,1900,450),'gnomes':(500,0,900,300)}
    shot,read2=1.36,4.5; white=959.0
    for nfr in (1,4,8):
        for noisy in (False,True):
            res={'single+bilinear':[],'superres':[]}
            for name,(x0,y0,x1,y1) in boxes.items():
                gt=im[y0:y1,x0:x1]; hr=srgb2lin(gt)*0.5
                # random sub-pixel shifts (multiples of 0.5 sensor px), first frame is the reference
                shifts=[(0.0,0.0)]+[(float(rng.integers(-3,4))*0.5,float(rng.integers(-3,4))*0.5) for _ in range(nfr-1)]
                nv=(lambda s:(shot*s*white+read2)/white**2/ (1 if noisy else 1e9)) 
                frames=make_frames(hr,shifts,nv,rng)
                # baseline: demosaic the reference frame, upscale x2 bilinear
                base=directional(frames[0],eps=0.02/ (1) ); base=np.clip(base,0,None)
                up=np.stack([ndzoom(base[...,c],2,order=1) for c in range(3)],-1)
                out,ws=superres(frames,shifts)
                out=fill(out,up)
                res['single+bilinear'].append(psnr(lin2srgb(up*2),gt)); res['superres'].append(psnr(lin2srgb(out*2),gt))
            print('frames %d %s:'%(nfr,'noisy' if noisy else 'clean'),'  '.join('%s %.2f dB'%(k,np.mean(v)) for k,v in res.items()))
