#!/usr/bin/env bash
# Background monitor: wait for run3 of the JSON-schema eval to finish, then run agg_compare.py
cd "/d/Quanta_Back_end/back_project/fawentong" || exit 1
LOG="run_jsonschema_final.log"
DONE="agg_compare_out.txt"
HBT="monitor_agg.hb"

echo "$(date '+%H:%M:%S') monitor start" >> "$HBT"
i=0
while [ $i -lt 80 ]; do
  i=$((i+1))
  if grep -q "wrote results_run3.json" "$LOG"; then
    echo "$(date '+%H:%M:%S') run3 complete detected" >> "$HBT"
    PY=/c/Users/卢泓桦/AppData/Local/Programs/Python/Python311/python.exe
    "$PY" agg_compare.py > "$DONE" 2>&1
    echo "$(date '+%H:%M:%S') agg done exit=$?" >> "$HBT"
    break
  fi
  # heartbeat
  echo "$(date '+%H:%M:%S') tick $i waiting (last log line: $(tail -n1 "$LOG"))" >> "$HBT"
  sleep 45
done
echo "$(date '+%H:%M:%S') monitor exit loop i=$i" >> "$HBT"
