import json, glob, collections
O='/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/ga/out2/'
tot=0; dupk=0; incons=0; ex=[]
for f in sorted(glob.glob(O+'facts-*.json')):
    d=json.load(open(f))
    for tax,concepts in d['facts'].items():
        for c,body in concepts.items():
            for u,arr in body['units'].items():
                g=collections.defaultdict(list)
                for n in arr:
                    g[(n.get('accn'),n.get('start'),n['end'])].append(n['val'])
                    tot+=1
                for k,v in g.items():
                    if len(v)>1:
                        dupk+=1
                        if len(set(v))>1:
                            incons+=1
                            if len(ex)<8: ex.append((f.split('/')[-1],tax,c,u,k,v))
print('nodes',tot,'dup(accn,start,end) keys',dupk,'with differing val',incons)
for e in ex: print(e)
