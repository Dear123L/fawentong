#!/usr/bin/env bash
# JSON-schema 加固后三跑评测编排（全路径 python，规避后台 shell 无 py 别名）
cd /d/Quanta_Back_end/back_project/fawentong || exit 1
PY="/c/Users/卢泓桦/AppData/Local/Programs/Python/Python311/python.exe"
TOTAL=38
for run in run1 run2 run3; do
  out="results_${run}.json"
  echo "===== $run (out=$out) ====="
  for i in $(seq 1 40); do
    line=$("$PY" count_done.py "$out" 2>/dev/null || echo "0/0")
    done=${line%/*}; total=${line#*/}
    done=${done:-0}; total=${total:-0}
    if [ "$done" -ge "$TOTAL" ]; then
      echo "$run 已完成 ($done/$TOTAL)，跳过"; break
    fi
    echo "$run: 已完成 $done/$TOTAL，启动 480s 预算块 (#$i)..."
    "$PY" run_eval.py "$out" "$run" 480
  done
done
echo "ALL DONE at $(date)"
