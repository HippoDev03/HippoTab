import sys, struct
from collections import defaultdict

data = open("spark_data.bin","rb").read()

def read_varint(b, i):
    shift = 0; result = 0
    while True:
        byte = b[i]; i += 1
        result |= (byte & 0x7f) << shift
        if not (byte & 0x80): break
        shift += 7
    return result, i

def parse_fields(b, start, end):
    """Yield (field_num, wiretype, value) where value is bytes/int/float depending on wiretype."""
    i = start
    while i < end:
        tag, i = read_varint(b, i)
        fn = tag >> 3; wt = tag & 7
        if wt == 0:
            v, i = read_varint(b, i); yield (fn, wt, v)
        elif wt == 1:
            v = struct.unpack('<d', b[i:i+8])[0]; i += 8; yield (fn, wt, v)
        elif wt == 2:
            ln, i = read_varint(b, i); yield (fn, wt, (i, i+ln)); i += ln
        elif wt == 5:
            v = struct.unpack('<f', b[i:i+4])[0]; i += 4; yield (fn, wt, v)
        else:
            raise ValueError(f"bad wiretype {wt} at {i}")

self_time = defaultdict(float)   # method -> self ms
total_time = defaultdict(float)  # method -> total ms

def parse_stnode(b, start, end):
    """StackTraceNode: 1=double time, 2=children, 3=class, 4=method. Returns node total time."""
    cls=None; meth=None; t=0.0; children=[]
    for fn, wt, val in parse_fields(b, start, end):
        if fn==1 and wt==1: t=val
        elif fn==2 and wt==2: children.append(val)
        elif fn==3 and wt==2: cls=b[val[0]:val[1]].decode('utf-8','replace')
        elif fn==4 and wt==2: meth=b[val[0]:val[1]].decode('utf-8','replace')
    name = f"{cls}.{meth}" if cls else (meth or "?")
    child_total=0.0
    for (cs,ce) in children:
        child_total += parse_stnode(b, cs, ce)
    total_time[name]+=t
    self_time[name]+= max(0.0, t-child_total)
    return t

# top level: field1 metadata, field2 repeated ThreadNode
threads=[]
for fn, wt, val in parse_fields(data, 0, len(data)):
    if fn==2 and wt==2: threads.append(val)

# ThreadNode: 1=name string, 2=children StackTraceNode, 3=double time
thread_info=[]
for (ts,te) in threads:
    name=None; children=[]; ttime=0.0
    for fn, wt, val in parse_fields(data, ts, te):
        if fn==1 and wt==2: name=data[val[0]:val[1]].decode('utf-8','replace')
        elif fn==2 and wt==2: children.append(val)
        elif fn==3 and wt==1: ttime=val
    thread_info.append((name, ttime, len(children)))
    for (cs,ce) in children:
        parse_stnode(data, cs, ce)

print("=== THREADS ===")
for n,t,c in sorted(thread_info, key=lambda x:-x[1])[:15]:
    print(f"{t:12.1f}ms  children={c}  {n}")

def show(d, title, filt=None):
    print(f"\n=== {title} ===")
    items=[(k,v) for k,v in d.items() if (filt is None or filt(k))]
    for name,v in sorted(items, key=lambda x:-x[1])[:40]:
        print(f"{v:12.1f}ms  {name}")

show(self_time, "TOP SELF-TIME (all methods)")
show(self_time, "SELF-TIME: hippo/papi/related", lambda k: any(s in k.lower() for s in ['hippo','placeholder','papi','mwtw']))
