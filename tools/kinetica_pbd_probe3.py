import math
def run(th0=30, T=60, h=1/240, g=9.81, L=0.18, v0=(0,0,0)):
    th=math.radians(th0)
    p=[L*math.sin(th), -L*math.cos(th), 0.0]; v=list(v0)
    E=lambda p,v: 0.5*sum(x*x for x in v)+g*p[1]
    E0=E(p,v); n=int(T/h); crosses=[]; prev=p[0]; minz=[1e9,-1e9]
    for k in range(n):
        e_before=E(p,v)
        v[1]-=g*h
        p=[p[i]+v[i]*h for i in range(3)]
        d=math.sqrt(sum(x*x for x in p)); p=[x*L/d for x in p]
        nn=[x/L for x in p]; vn=sum(v[i]*nn[i] for i in range(3)); v=[v[i]-vn*nn[i] for i in range(3)]
        ke=e_before-g*p[1]
        sp=math.sqrt(sum(x*x for x in v))
        if ke>0 and sp>1e-9:
            s=math.sqrt(2*ke)/sp; v=[x*s for x in v]
        if prev<0 and p[0]>=0:
            t=(k+ (-prev)/(p[0]-prev))*h; crosses.append(t)
        prev=p[0]; minz=[min(minz[0],p[2]),max(minz[1],p[2])]
    Ts=[crosses[i+1]-crosses[i] for i in range(len(crosses)-1)]
    return round((E(p,v)-E0)/abs(E0+g*L)*100,4), [round(x,4) for x in Ts[:2]], round(2*math.pi*math.sqrt(L/g),4), minz
print(run()); print(run(th0=5)); print(run(g=1.62)); print(run(th0=30, v0=(0,0,0.3)))
