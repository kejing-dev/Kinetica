import math
h=1/240; g=9.81; L=0.18
th=math.radians(30)
p=[L*math.sin(th), -L*math.cos(th), 0.0]; v=[0,0,0]
def E(p,v): return 0.5*sum(x*x for x in v)+g*p[1]
E0=E(p,v); n=int(60/h); crosses=[]; prev=p[0]
for k in range(n):
    pp=p[:]
    v[1]-=g*h
    p=[p[i]+v[i]*h for i in range(3)]
    d=math.sqrt(sum(x*x for x in p)); p=[x*L/d for x in p]
    v=[(p[i]-pp[i])/h for i in range(3)]
    if prev<0 and p[0]>=0: crosses.append(k*h)
    prev=p[0]
print('drift %', (E(p,v)-E0)/abs(E0-g*(-L))*100)
T=[crosses[i+1]-crosses[i] for i in range(len(crosses)-1)]
print('T',T[:3],'ideal',2*math.pi*math.sqrt(L/g))
