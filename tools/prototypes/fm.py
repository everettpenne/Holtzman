# Host prototype of the HDR+-style frequency-domain merge and of the frame-weighting rules; see docs/ix-pipeline.md. Needs sr.py and dm.py beside it.
import numpy as np, sys
sys.path.insert(0,__import__("os").path.dirname(__file__))
from dm import *
from sr import make_frames
W=8  # tile size per colour plane (16x16 raw pixels)
def win2d(n=W):
    w=0.5-0.5*np.cos(2*np.pi*(np.arange(n)+0.5)/n); return np.outer(w,w)
def planes(m):  # RGGB mosaic -> 4 planes of half size
    return [m[0::2,0::2],m[0::2,1::2],m[1::2,0::2],m[1::2,1::2]]
def unplanes(p):
    H,Wd=p[0].shape; m=np.zeros((H*2,Wd*2)); m[0::2,0::2]=p[0]; m[0::2,1::2]=p[1]; m[1::2,0::2]=p[2]; m[1::2,1::2]=p[3]; return m
def tiles(a,stride=W//2):
    H,Wd=a.shape; ys=range(0,H-W+1,stride); xs=range(0,Wd-W+1,stride)
    return np.array([[a[y:y+W,x:x+W] for x in xs] for y in ys]),len(ys),len(xs)
def freq_merge(frames,shot,read2,c=8.0,ref=0):
    """HDR+-style: per tile and frequency, shrink the difference to the reference by a Wiener gain; overlap-add with a raised-cosine window."""
    win=win2d(); out_planes=[]
    P=[planes(f) for f in frames]
    n=len(frames)
    for k in range(4):
        ref_t,ny,nx=tiles(P[ref][k]); ref_t=ref_t*win
        Tref=np.fft.fft2(ref_t)
        acc=Tref.copy()  # sum over frames of the merged spectra (ref counted once)
        mean=np.mean(ref_t/ np.maximum(win,1e-6),axis=(2,3)) if False else None
        sig2=(shot*np.maximum(tiles(P[ref][k])[0].mean(axis=(2,3)),0)+read2)   # per-tile single-sample noise variance
        sig2=sig2[...,None,None]*(win**2).sum()                                # DFT noise power of a windowed tile
        for z in range(n):
            if z==ref: continue
            Tz=np.fft.fft2(tiles(P[z][k])[0]*win)
            D=Tref-Tz
            A=(np.abs(D)**2)/(np.abs(D)**2+c*sig2)
            acc+=Tz+A*(Tref-Tz)
        T=acc/n
        t=np.real(np.fft.ifft2(T))
        # overlap-add (raised cosine at 50% overlap sums to 1 only for the squared window; use the analysis window once and normalise)
        H,Wd=P[ref][k].shape; o=np.zeros((H,Wd)); wsum=np.zeros((H,Wd)); s=W//2
        for i in range(ny):
            for j in range(nx):
                o[i*s:i*s+W,j*s:j*s+W]+=t[i,j]; wsum[i*s:i*s+W,j*s:j*s+W]+=win
        out_planes.append(np.where(wsum>1e-3,o/np.maximum(wsum,1e-3),P[ref][k]))
    return unplanes(out_planes)

def mean_merge(frames): return np.mean(frames,axis=0)
def weighted_merge(frames,shot,read2,tol=3.0,ref=0):
    """Our engine's rule on the cell grid: weight = exp(-0.5 d^2/(tol^2 var/9+1)) from cell sums, applied to all four samples."""
    r=frames[ref]
    def cs(m): return m[0::2,0::2]+m[0::2,1::2]+m[1::2,0::2]+m[1::2,1::2]
    cr=cs(r); var=2*(shot*np.maximum(cr,0)+4*read2)
    acc=r.copy(); ws=np.ones(cr.shape)
    accp=planes(r); accp=[a.copy() for a in accp]
    for z,f in enumerate(frames):
        if z==ref: continue
        d=cs(f)-cr; w=np.exp(-0.5*d*d/(tol**2*var/9+1)); w=np.where(w<0.02,0,w)
        for a,p in zip(accp,planes(f)): a+=w*p
        ws+=w
    return unplanes([a/ws for a in accp])

if __name__=='__main__':
    im=np.asarray(ImageOps.exif_transpose(Image.open('/tmp/ab/google.jpg')).convert('RGB'),dtype=np.float64)
    rng=np.random.default_rng(11); white=959.0
    boxes={'label':(1450,880,2050,1380),'rug':(100,1000,700,1500),'figure':(1500,150,2100,650)}
    for iso,shot,read2 in ((800,1.36,4.5),):
        for scenario in ('static','shake +-1px','shake +-1px + a moving patch'):
            res={}
            for name,(x0,y0,x1,y1) in boxes.items():
                gt=srgb2lin(im[y0:y1,x0:x1]); hr=gt*0.5*white   # counts above black at hi-res
                nfr=6
                if scenario=='static': shifts=[(0.0,0.0)]*nfr
                else: shifts=[(0.0,0.0)]+[(float(rng.integers(-2,3))*0.5,float(rng.integers(-2,3))*0.5) for _ in range(nfr-1)]
                nv=lambda s:(shot*s+read2)
                frames=make_frames(hr/white,shifts,lambda s:(shot*s*white+read2)/white**2,rng)  # make_frames expects 0..1 units
                frames=[f*white for f in frames]
                if 'moving' in scenario:
                    H,Wd=frames[0].shape
                    for zi in range(1,nfr):   # a patch of the frame content displaced by 6 px in the non-reference frames
                        y=H//3; x=Wd//3; frames[zi][y:y+60,x:x+60]=np.roll(frames[zi],6,axis=1)[y:y+60,x:x+60]
                # compare after demosaic against the box-downsampled truth at sensor res (what the reference frame would be without noise)
                from sr import box_down
                sens=box_down(hr/white*2.0,2)  # 0..1 scaled like srgb input; frames/white are in 0..0.5 -> *2
                ref_clean=lin2srgb(sens)
                for tag,mf in (('ref frame only',lambda fr:fr[0]),('mean of 6',mean_merge),('our weighted merge',lambda fr:weighted_merge(fr,shot,read2)),('freq merge c=8',lambda fr:freq_merge(fr,shot,read2,8.0)),('freq merge c=2',lambda fr:freq_merge(fr,shot,read2,2.0))):
                    m=mf(frames); out=lin2srgb(np.clip(directional(m/white*2.0,eps=0.02),0,None))
                    res.setdefault(tag,[]).append(psnr(out,ref_clean))
            print('ISO %d %-28s'%(iso,scenario),'  '.join('%s %.2f'%(k,np.mean(v)) for k,v in res.items()))
