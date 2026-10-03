# -*- coding: utf-8 -*-
import json, sys, os
f = sys.argv[1]
if not os.path.exists(f):
    print("0/0"); sys.exit()
try:
    a = json.load(open(f, encoding="utf-8"))
except Exception:
    print("0/0"); sys.exit()
done = sum(1 for x in a if not (str(x.get("answer", "")).startswith("__ERROR__")
                                or str(x.get("answer", "")).startswith("__TIMEOUT__")))
print(f"{done}/{len(a)}")
