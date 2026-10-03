#!/bin/bash
# 监督脚本：自动续跑 run_eval.py 直到 150 题全部完成（非 ERROR/TIMEOUT）
# 用法： OUT=results_x.json EVAL_TESTSET=contract_...json SESSION=v3x nohup bash supervise_eval.sh
OUT="${OUT:-results_v3.json}"
PY="C:/Users/卢泓桦/AppData/Local/Programs/Python/Python311/python.exe"
export EVAL_TESTSET="${EVAL_TESTSET:-contract_qa_testset_v3.json}"
export EVAL_BASE="${EVAL_BASE:-http://localhost:8082/api/rag/chatAgent/multiDebug}"
SESS="${SESSION:-v3open2}"
cd /d/Quanta_Back_end/back_project/fawentong
while true; do
  cnt=$("$PY" -c "import json,sys;d=json.load(open('$OUT'));print(len([x for x in d if x.get('status') not in ('ERROR','TIMEOUT')]))" 2>/dev/null || echo 0)
  echo "[supervisor] done=$cnt / 150"
  if [ "$cnt" -ge 150 ]; then
    echo "[supervisor] ALL DONE"
    break
  fi
  "$PY" run_eval.py "$OUT" "$SESS" 540
  echo "[supervisor] round exited, re-checking..."
  sleep 2
done
echo "[supervisor] finished at $(date)"
