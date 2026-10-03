import json, statistics
from eval_scorer import load_testset, evaluate, _key_metrics

ts = load_testset()
def agg(files):
    per=[]
    for f in files:
        r=json.load(open(f,encoding='utf-8'))
        rep=evaluate(ts,r)
        per.append(_key_metrics(rep))
    out={}
    for k in per[0]:
        vals=[p[k] for p in per]
        out[k]={"values":vals,"mean":round(statistics.mean(vals),4),
                "std":round(statistics.stdev(vals),4) if len(vals)>1 else 0.0}
    return out

print("=== 本次(JSON-schema加固) 三跑 ===")
for k,v in agg(["results_run1.json","results_run2.json","results_run3.json"]).items():
    print(f"  {k:12s} 各次={v['values']}  均值={v['mean']}  标准差={v['std']}")

print("\n=== 加固前(beforefix, 无确定性解析) 三跑 ===")
for k,v in agg(["results_run1_beforefix.json","results_run2_beforefix.json","results_run3_beforefix.json"]).items():
    print(f"  {k:12s} 各次={v['values']}  均值={v['mean']}  标准差={v['std']}")

print("\n=== pre-JSON-schema(确定性解析已上, 本次加固前) 三跑 ===")
for k,v in agg(["results_prejsonschema_run1.json","results_prejsonschema_run2.json","results_prejsonschema_run3.json"]).items():
    print(f"  {k:12s} 各次={v['values']}  均值={v['mean']}  标准差={v['std']}")
