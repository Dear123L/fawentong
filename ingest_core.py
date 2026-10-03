# -*- coding: utf-8 -*-
"""
核心层入库：民法典·合同编(第463-988条) + 合同编通则解释(全量)
按"第X条"语义边界切分（条后全角空格 　= 真实条款头），纯 BM25 mapping 写入 ES。
clauseId 格式：{sourceKey}-第{N}条  （如 民法典-第585条）
预期条数：民法典 526 + 通则解释 69 = 595。
"""
import json, re, urllib.request, urllib.error, urllib.parse, zipfile

ES = "http://localhost:9200"
IDX = "rag_knowledge_base"
KB_ID = 1

# (sourceKey, 文件路径, 条号范围)  None 范围 = 全量
SOURCES = [
    ("民法典",
     r"D:/Quanta_Back_end/back_project/fawentong/文件汇总/法规案例通-法规库/民法典/中华人民共和国民法典.docx",
     (463, 988)),
    ("合同编通则解释",
     r"D:/Quanta_Back_end/back_project/fawentong/文件汇总/法规案例通-法规库/合同类/最高人民法院关于适用《中华人民共和国民法典》合同编通则若干问题的解释.docx",
     None),
]

CN = {'零':0,'一':1,'二':2,'两':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9,'〇':0}
def cn(s):
    if s.isdigit():
        return int(s)
    t, n = 0, 0
    for c in s:
        if c in CN: n = CN[c]
        elif c == '十': t += (n if n else 1)*10; n = 0
        elif c == '百': t += n*100; n = 0
        elif c == '千': t += n*1000; n = 0
    return t + n

# 真实条款头：第X条 后必须紧跟全角空格（排除"第X条的规定/第X条、第Y条"等正文引用）
ART = re.compile(r'第\s*([0-9零一二三四五六七八九十百千]+)\s*条\u3000')

def docx_text(p):
    z = zipfile.ZipFile(p)
    xml = z.read('word/document.xml').decode('utf-8', 'ignore')
    # 用 [^<]* 避免把 <w:autoSpaceDE/> 等排版标签当正文抽进来
    return ''.join(re.findall(r'<w:t[^>]*>([^<]*)</w:t>', xml))

def count_items(body):
    return len(re.findall(r'（[一二三四五六七八九十百零]+）', body))

def es(method, path, body=None):
    url = ES + path
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')

MAPPING = {
    "mappings": {
        "properties": {
            "content":    {"type": "text"},
            "vector":     {"type": "dense_vector", "dims": 1024, "element_type": "float", "index": True, "similarity": "cosine"},
            "kbId":       {"type": "long"},
            "docId":      {"type": "long"},
            "userId":     {"type": "long"},
            "chunkIndex": {"type": "integer"},
            "createdAt":  {"type": "long"},
            "sourceKey":  {"type": "keyword"},
            "articleNo":  {"type": "integer"},
            "tier":       {"type": "integer"},
            "canonicalId":{"type": "keyword"},
            "items":      {"type": "integer"},
        }
    }
}

def es_bulk(docs, chunk=100):
    """用 _bulk 批量写入（ES 8.x 单条 PUT 约 2s，595 条约 20 分钟；bulk 秒级完成）。"""
    n_ok = 0
    for i in range(0, len(docs), chunk):
        part = docs[i:i + chunk]
        payload = ""
        for d in part:
            payload += json.dumps({"index": {"_index": IDX, "_id": d["_id"]}}, ensure_ascii=False) + "\n"
            payload += json.dumps(d["doc"], ensure_ascii=False) + "\n"
        req = urllib.request.Request(ES + "/_bulk", data=payload.encode("utf-8"), method="POST")
        req.add_header("Content-Type", "application/x-ndjson; charset=utf-8")
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                resp = json.loads(r.read().decode("utf-8"))
            if resp.get("errors"):
                print(f"  [WARN] bulk chunk {i} 有错误:", str(resp)[:300])
            else:
                n_ok += len(part)
        except Exception as e:
            print(f"  [WARN] bulk chunk {i} 失败: {e}")
    return n_ok

def main():
    # 1) 重建索引（备份已在 baselines/old_kb_c1c6.json）
    st, _ = es("HEAD", "/" + IDX)
    if st == 200:
        es("DELETE", "/" + IDX)
        print("已删除旧索引", IDX)
    st, msg = es("PUT", "/" + IDX, MAPPING)
    print("建索引:", st, msg[:200])

    all_docs = []
    for source_key, path, rng in SOURCES:
        text = docx_text(path)
        heads = list(ART.finditer(text))
        doc_id = 0
        for i, m in enumerate(heads):
            no = cn(m.group(1))
            if rng and not (rng[0] <= no <= rng[1]):
                continue
            nxt = heads[i+1].start() if i+1 < len(heads) else len(text)
            body = text[m.start():nxt].strip()
            if not body:
                continue
            cid = f"{source_key}-第{no}条"
            doc = {
                "content": body,
                "kbId": KB_ID,
                "docId": doc_id,
                "userId": 1,
                "chunkIndex": 0,
                "createdAt": 0,
                "sourceKey": source_key,
                "articleNo": no,
                "tier": 1,
                "canonicalId": cid,
                "items": count_items(body),
            }
            all_docs.append({"_id": cid, "doc": doc})
            doc_id += 1
        print(f"  {source_key}: 收集 {doc_id} 条 (范围={rng})")

    n_ok = es_bulk(all_docs, chunk=100)
    es("POST", f"/{IDX}/_refresh")
    st, msg = es("GET", f"/{IDX}/_count")
    print("入库完成，当前文档数:", msg)
    print("本批写入(含范围过滤):", n_ok)

if __name__ == "__main__":
    main()
