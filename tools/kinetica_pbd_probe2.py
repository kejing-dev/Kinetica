import math
def run(mode, th0=30, T=60, h=1/240, g=9.81, L=0.18):
    th=math.radians(th0)
    p=[L*math.sin(th), -L*math.cos(th), 0.0]; v=[0,0,0]
    E=lambda p,v: 0.5*sum(x*x for x in v)+g*p[1]
    E0=E(p,v); n=int(T/h); crosses=[]; prev=p[0]
    for k in range(n):
        if mode=='rattle':
            # velocity verlet (RATTLE-like): half kick, drift, project, half kick, velocity project
            v[1]-=g*h/2
            p=[p[i]+v[i]*h for i in range(3)]
            d=math.sqrt(sum(x*x for x in p)); p=[x*L/d for x in p]
            v[1]-=g*h/2
        else:
            v[1]-=g*h
            p=[p[i]+v[i]*h for i in range(3)]
            d=math.sqrt(sum(x*x for x in p)); p=[x*L/d for x in p]
        nn=[x/L for x in p]; vn=sum(v[i]*nn[i] for i in range(3)); v=[v[i]-vn*nn[i] for i in range(3)]
        if prev<0 and p[0]>=0:
            t=(k+ (-prev)/(p[0]-prev))*h; crosses.append(t)
        prev=p[0]
    Ts=[crosses[i+1]-crosses[i] for i in range(len(crosses)-1)]
    return (E(p,v)-E0)/abs(E0+g*L)*100, Ts[0], 2*math.pi*math.sqrt(L/g)
for m in ['proj','rattle']:
    print(m, run(m), run(m,th0=5))
