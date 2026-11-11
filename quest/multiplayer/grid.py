import zlib,struct,sys,os,re
d=sys.argv[1]
names=sorted([f[:-4] for f in os.listdir(d) if f.endswith('.ppm')],key=lambda n:int(re.sub(r'\D','',n) or 0))
ims=[]
for n in names:
    b=open(f'{d}/{n}.ppm','rb').read();p=b.split(b'\n',3);w,h=map(int,p[1].split());px=p[3]
    ims.append([b''.join(px[(y*w+x)*3:(y*w+x)*3+3] for x in range(0,w,3)) for y in range(0,h,3)])
W,H=len(ims[0][0])//3,len(ims[0]); cols=3
for g in range(0,len(ims),9):
    chunk=ims[g:g+9]; rows=(len(chunk)+cols-1)//cols; out=[]
    for gy in range(rows):
        for y in range(H):
            out.append(b''.join(chunk[gy*cols+gx][y] if gy*cols+gx<len(chunk) else b'\0'*W*3 for gx in range(cols)))
    raw=b''.join(b'\0'+l for l in out)
    c=lambda t,x:struct.pack('>I',len(x))+t+x+struct.pack('>I',zlib.crc32(t+x)&0xffffffff)
    fn=f'{d}/grid{g//9}.png'
    open(fn,'wb').write(b'\x89PNG\r\n\x1a\n'+c(b'IHDR',struct.pack('>IIBBBBB',W*cols,H*rows,8,2,0,0,0))+c(b'IDAT',zlib.compress(raw,6))+c(b'IEND',b''))
    print(fn, names[g:g+9])
