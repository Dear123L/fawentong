import urllib.request, json
ES = "http://localhost:9200"

def es_post(path, body):
    req = urllib.request.Request(ES + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))

phrases = ["离婚", "遗产", "遗嘱", "交通事故", "交通肇事", "工伤", "试用期", "劳动仲裁",
           "解除劳动合同", "劳动者", "用人单位", "商标侵权", "注册商标", "抢注",
           "虚开发票", "税务", "个税", "所得税", "股东", "消费者权益", "假货", "专利", "著作权"]

print(f"{'短语':<8} {'总命中':>6}  命中的 sourceKey(前3)")
for p in phrases:
    body = {"size": 0, "query": {"match": {"content": p}},
            "aggs": {"src": {"terms": {"field": "sourceKey", "size": 15}}}}
    d = es_post("/rag_knowledge_base/_search", body)
    total = d["hits"]["total"]["value"]
    srcs = [f"{b['key']}({b['doc_count']})" for b in d["aggregations"]["src"]["buckets"][:3]]
    print(f"{p:<8} {total:>6}  {' '.join(srcs)}")
