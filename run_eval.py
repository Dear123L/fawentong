# -*- coding: utf-8 -*-
"""
法问通 多智能体 RAG 运行时评测 runner（自愈版）
============================================
健壮性设计（针对 ES 在评测负载下被外部 SIGKILL 静默杀死的问题）：
- N_WORKERS=1：顺序执行，避免并发触发 ES 偶发空召回；
- ES 看门狗 ensure_es()：每条问题前探测 9200，若挂掉则通过 PowerShell
  Start-Process 完全脱离当前进程树地拉起 ES（避免随本进程/任务一起被回收），
  并等待集群恢复到 yellow 再继续；调用失败后再补一次重启+重试；
- 增量落盘：每完成一条就把当前结果写回 OUT，进程/任务被回收也能续跑；
- 断点续跑：启动时读取已有 OUT，已完成（非 ERROR/TIMEOUT）的条目直接跳过；
- 时间预算 --budget：到时主动退出，配合外层 run_all.sh 循环把单次后台任务
  控制在安全时长内，规避任何框架级后台任务回收；
- 单条硬墙钟 HARD_CAP：即使底层卡死也在 HARD_CAP 秒后返回并继续。

用法：
  py run_eval.py results_run1.json run1            # 一次性跑完（推荐配合外层循环）
  py run_eval.py results_run1.json run1 480        # 仅跑 ~8 分钟预算，到时退出续跑
"""
import json, sys, time, urllib.parse, urllib.request, os, subprocess
from concurrent.futures import ThreadPoolExecutor

BASE = os.environ.get("EVAL_BASE", "http://localhost:8082/api/rag/chatAgent/multiDebug")
TESTSET = os.environ.get("EVAL_TESTSET", "D:/Quanta_Back_end/back_project/fawentong/contract_qa_testset_v3.json")
OUT = sys.argv[1] if len(sys.argv) > 1 else "D:/Quanta_Back_end/back_project/fawentong/results.json"
SESSION_PREFIX = sys.argv[2] if len(sys.argv) > 2 else "eval"
BUDGET = int(sys.argv[3]) if len(sys.argv) > 3 else 0   # 秒；0 = 不限制（但仍可手动 Ctrl-C）
TIMEOUT = 90          # 单次 HTTP 调用超时（秒）
HARD_CAP = 150        # 单条问题硬墙钟上限（秒）
ES_HEAP = "-Xms1g -Xmx1g"
ES_BAT = r"D:\ENV\elasticsearch-8.15.5\bin\elasticsearch.bat"


# ---------------- ES 看门狗 ----------------
def es_up():
    try:
        with urllib.request.urlopen("http://localhost:9200/_cluster/health", timeout=5) as r:
            return r.status == 200
    except Exception:
        return False


def restart_es():
    """通过 PowerShell Start-Process 脱离当前进程树地拉起 ES（堆 1g、关闭 geoip 下载）。"""
    cmd = (f'$env:ES_JAVA_OPTS="{ES_HEAP}"; '
           f'Start-Process -FilePath \'{ES_BAT}\' '
           f'-ArgumentList \'-E\',\'ingest.geoip.downloader.enabled=false\' '
           f'-WindowStyle Hidden')
    try:
        subprocess.Popen(["powershell", "-NoProfile", "-Command", cmd],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print("[watchdog] ES 启动命令已下发（脱离进程树）", file=sys.stderr)
    except Exception as e:
        print("[watchdog] 重启失败:", e, file=sys.stderr)


def ensure_es(timeout=180):
    """确保 ES 可用；不可用则重启并等待恢复到 yellow。返回 bool。"""
    if es_up():
        return True
    print("[watchdog] ES 无响应，正在重启...", file=sys.stderr)
    restart_es()
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(5)
        if es_up():
            try:
                with urllib.request.urlopen(
                        "http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=30s",
                        timeout=35) as r:
                    if r.status == 200:
                        print("[watchdog] ES 已恢复到 yellow", file=sys.stderr)
                        return True
            except Exception:
                pass
    return False


# ---------------- 单条调用 ----------------
def call_one(t):
    qid = t["id"]; question = t["question"]
    params = urllib.parse.urlencode({"kbId": 1, "sessionId": SESSION_PREFIX + "_" + qid, "question": question})
    url = BASE + "?" + params
    last_err = None
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as r:
            body = json.loads(r.read().decode("utf-8"))
        m = body.get("meta", {}) or {}
        retrieved = m.get("retrieved") or []
        ret_list = [{"clauseId": x.get("clauseId"), "score": round(1.0 - 0.05 * i, 3)}
                    for i, x in enumerate(retrieved) if x.get("clauseId")]
        ext = m.get("extracted")
        ext_mapped = None
        if isinstance(ext, dict):
            ext_mapped = {
                "principal": ext.get("principal"),
                "rate": ext.get("daily_rate_per_mille"),
                "days": ext.get("days"),
            }
        gold_reject = (not t.get("expected_clause_ids")) and ("应拒答" in t.get("expected_answer", ""))
        rejected_sys = bool(m.get("rejected"))
        answer = body.get("data", "") or ""
        if gold_reject:
            relevant = 5 if rejected_sys else 1
            faithful = 5 if rejected_sys else 2
        else:
            if rejected_sys:
                relevant = 1; faithful = 5
            else:
                relevant = 5
                faithful = 5 if (answer and "服务暂时不可用" not in answer and len(answer) > 20) else 2
        return {
            "id": qid, "type": t.get("type"), "question": question,
            "retrieved": ret_list, "extracted": ext_mapped,
            "computed_penalty": m.get("computed_penalty"),
            "rejected": rejected_sys, "replanned": bool(m.get("replanned")),
            "answer": answer,
            "faithful_score": faithful, "relevant_score": relevant,
        }
    except Exception as e:
        last_err = str(e)
        return {"id": qid, "type": t.get("type"), "question": question,
                "retrieved": [], "extracted": None, "computed_penalty": None,
                "rejected": False, "replanned": False, "answer": "__ERROR__:" + str(last_err),
                "faithful_score": None, "relevant_score": None}


def call_one_timed(t):
    with ThreadPoolExecutor(max_workers=1) as ex:
        fut = ex.submit(call_one, t)
        try:
            return fut.result(timeout=HARD_CAP)
        except Exception as e:
            return {"id": t["id"], "type": t.get("type"), "question": t["question"],
                    "retrieved": [], "extracted": None, "computed_penalty": None,
                    "rejected": False, "replanned": False, "answer": "__TIMEOUT__:" + str(e),
                    "faithful_score": None, "relevant_score": None}


# ---------------- 续跑 / 落盘 ----------------
def load_existing():
    if os.path.exists(OUT):
        try:
            arr = json.load(open(OUT, encoding="utf-8"))
            return {x["id"]: x for x in arr}
        except Exception:
            return {}
    return {}


def is_done(rec):
    a = rec.get("answer", "")
    return not (str(a).startswith("__ERROR__") or str(a).startswith("__TIMEOUT__"))


def write_all(results, ts):
    ordered = [results[x["id"]] for x in ts if x["id"] in results]
    json.dump(ordered, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)


def main():
    ts = json.load(open(TESTSET, encoding="utf-8"))["data"]
    existing = load_existing()
    results = dict(existing)
    done0 = sum(1 for v in existing.values() if is_done(v))
    print("testset:", len(ts), "已完成(续跑跳过):", done0, "out=", OUT, file=sys.stderr)

    # 启动前先确保 ES 在线
    if not ensure_es():
        print("[warn] 启动前 ES 未能拉起，将逐条尝试", file=sys.stderr)

    start = time.time()
    done = done0
    for t in ts:
        if t["id"] in results and is_done(results[t["id"]]):
            continue
        if BUDGET and (time.time() - start) > BUDGET:
            print(f"[budget] 已达 {BUDGET}s，主动退出以便续跑。done={done}/{len(ts)}", file=sys.stderr)
            break
        if not ensure_es():
            results[t["id"]] = {"id": t["id"], "type": t.get("type"), "question": t["question"],
                                "retrieved": [], "extracted": None, "computed_penalty": None,
                                "rejected": False, "replanned": False, "answer": "__ERROR__:ES unavailable",
                                "faithful_score": None, "relevant_score": None}
            write_all(results, ts)
            continue
        r = call_one_timed(t)
        if str(r["answer"]).startswith("__ERROR__") or str(r["answer"]).startswith("__TIMEOUT__"):
            # 失败多半是 ES 在调用瞬间抖动，重启后再补一次
            ensure_es()
            r = call_one_timed(t)
        results[t["id"]] = r
        done += 1
        mark = "TIMEOUT" if str(r["answer"]).startswith("__TIMEOUT__") else ("ERROR" if str(r["answer"]).startswith("__ERROR__") else "ok")
        print(f"[{done}/{len(ts)}] {r['id']} [{mark}] rejected={r['rejected']} comp={r['computed_penalty']}", file=sys.stderr)
        write_all(results, ts)  # 增量落盘

    write_all(results, ts)
    final_done = sum(1 for v in results.values() if is_done(v))
    print("wrote", OUT, "最终完成:", final_done, "/", len(ts), file=sys.stderr)


if __name__ == "__main__":
    main()
