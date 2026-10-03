#!/usr/bin/env bash
# 法问通 三跑评测编排：每跑用 480s(8min) 预算块循环，断点续跑，规避后台任务被回收。
cd /d/Quanta_Back_end/back_project/fawentong || exit 1
TOTAL=38
for run in run1 run2 run3; do
  out="results_${run}.json"
  echo "===== $run (out=$out) ====="
  for i in $(seq 1 30); do
    line=$(py count_done.py "$out" 2>/dev/null || echo "0/0")
    done=${line%/*}; total=${line#*/}
    done=${done:-0}; total=${total:-0}
    if [ "$done" -ge "$TOTAL" ]; then
      echo "$run 已完成 ($done/$TOTAL)，跳过"; break
    fi
    echo "$run: 已完成 $done/$TOTAL，启动一个 480s 预算块 (#$i)..."
    py run_eval.py "$out" "$run" 480
  done
done
echo "ALL DONE at $(date)"
