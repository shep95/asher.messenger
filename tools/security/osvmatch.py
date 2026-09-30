#!/usr/bin/env python3
"""Match dependency coordinates against a local checkout of github/advisory-database (OSV JSON)."""
import json,sys,glob,re,os
from functools import cmp_to_key
DB=sys.argv[1]; eco=sys.argv[2]; depfile=sys.argv[3]

def vparse(v):
    v=v.lower()
    parts=re.split(r'[.\-_+]',v)
    out=[]
    for p in parts:
        if p=='' : continue
        m=re.match(r'^(\d+)([a-z]+)?(\d*)$',p)
        if p.isdigit(): out.append((1,int(p),''))
        elif m and m.group(2):  # e.g. 1rc2
            out.append((1,int(m.group(1)),'')); out.append((0,0,m.group(2)+m.group(3)))
        else: out.append((0,0,p))
    return out
QUAL={'alpha':-6,'a':-6,'beta':-5,'b':-5,'milestone':-4,'m':-4,'rc':-3,'cr':-3,'snapshot':-2,'preview':-2,'dev':-2,'':-1,'ga':-1,'final':-1,'release':-1,'sp':1}
def qrank(q):
    m=re.match(r'^([a-z]*)(\d*)$',q); base=QUAL.get(m.group(1) if m else q, 0 if not m or m.group(1)=='' else 0)
    return (base, int(m.group(2)) if m and m.group(2) else 0, q)
def vcmp(a,b):
    A,B=vparse(a),vparse(b)
    n=max(len(A),len(B))
    for i in range(n):
        x=A[i] if i<len(A) else (1,0,''); y=B[i] if i<len(B) else (1,0,'')
        if x[0]!=y[0]:  # numeric vs qualifier: qualifier < numeric (1.0-rc < 1.0 == 1.0.0)
            if x[0]==0: return -1 if qrank(x[2])[0]<0 else 1
            else: return 1 if qrank(y[2])[0]<0 else -1
        if x[0]==1:
            if x[1]!=y[1]: return -1 if x[1]<y[1] else 1
        else:
            qa,qb=qrank(x[2]),qrank(y[2])
            if qa!=qb: return -1 if qa<qb else 1
    return 0
def affected(ver, rng):
    intro=None; fixed=None; last=None
    events=rng.get('events',[])
    # evaluate each introduced..fixed pair
    hit=False
    cur_intro=None
    for e in events:
        if 'introduced' in e:
            cur_intro=e['introduced']
        elif 'fixed' in e or 'last_affected' in e:
            lo=cur_intro; hi=e.get('fixed'); la=e.get('last_affected')
            ok = (lo=='0' or lo is None or vcmp(ver,lo)>=0)
            if hi: ok = ok and vcmp(ver,hi)<0
            if la: ok = ok and vcmp(ver,la)<=0
            if ok: hit=True
            cur_intro=None
    if cur_intro is not None:
        if cur_intro=='0' or vcmp(ver,cur_intro)>=0: hit=True
    return hit

deps=[]
for line in open(depfile):
    line=line.strip()
    m=re.match(r'^([^:\s]+):([^:\s]+):(?:[^:\s]+:)?(?:[^:\s]+:)?([0-9][^:\s]*)',line)
    if not m: 
        m=re.match(r'^([^:\s]+):([^:\s]+):[^:\s]+:([0-9][^:\s]*)',line)
    if not m: continue
    g,a,v=m.group(1),m.group(2),m.group(3)
    name=f"{g}:{a}" if eco=='Maven' else a
    deps.append((name,v,line))
index={}
for f in glob.glob(f"{DB}/advisories/github-reviewed/**/*.json",recursive=True):
    try: adv=json.load(open(f))
    except: continue
    for aff in adv.get('affected',[]):
        pkg=aff.get('package',{})
        if pkg.get('ecosystem')!=eco: continue
        index.setdefault(pkg.get('name','').lower(),[]).append((adv,aff))
found=[]
for name,v,line in deps:
    for adv,aff in index.get(name.lower(),[]):
        hit=False
        for rng in aff.get('ranges',[]):
            if rng.get('type') in ('ECOSYSTEM','SEMVER') and affected(v,rng): hit=True
        if not hit and v in aff.get('versions',[]): hit=True
        if hit:
            sev=adv.get('database_specific',{}).get('severity','?')
            fixed=[e['fixed'] for r in aff.get('ranges',[]) for e in r.get('events',[]) if 'fixed' in e]
            found.append((sev,name,v,adv['id'],adv.get('aliases',[]),adv.get('summary',''),fixed, adv.get('withdrawn')))
order={'CRITICAL':0,'HIGH':1,'MODERATE':2,'LOW':3}
found.sort(key=lambda x:(order.get(x[0],9),x[1]))
for sev,name,v,i,al,s,fx,wd in found:
    if wd: continue
    print(f"{sev:8s} {name} {v}  {i} {','.join(al)}  fixed>={','.join(fx) or '?'}  {s[:90]}")
print(f"# {len(deps)} deps checked, {len([f for f in found if not f[7]])} advisory matches")
