import sys, math
from PIL import Image, ImageDraw
S=192*4
im=Image.new("RGBA",(S,S),(0,0,0,0))
d=ImageDraw.Draw(im)
d.rounded_rectangle([0,0,S-1,S-1],radius=S//5,fill=(178,34,52,255))
# ECG trace
pts=[(0.08,0.55),(0.28,0.55),(0.34,0.47),(0.40,0.55),(0.45,0.55),(0.50,0.80),(0.56,0.18),(0.62,0.66),(0.66,0.55),(0.74,0.55),(0.80,0.45),(0.86,0.55),(0.92,0.55)]
pts=[(x*S,y*S) for x,y in pts]
d.line(pts,fill=(255,255,255,255),width=S//20,joint="curve")
for x,y in (pts[0],pts[-1]):
    r=S//40; d.ellipse([x-r,y-r,x+r,y+r],fill=(255,255,255,255))
im=im.resize((192,192),Image.LANCZOS)
im.save(sys.argv[1])
