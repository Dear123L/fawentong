# -*- coding: utf-8 -*-
"""
全量入库：把 法规案例通-法规库 下全部法律法规灌入 rag_knowledge_base。

覆盖范围（约 2100 条）：
  扩展层：劳动合同法、住房租赁条例(租房条例)、民间借贷规定(.doc)
  全量层：民法典(全编 1-1260)、合同编通则解释、民事诉讼法、社会保险法、劳动法、
          劳动保障监察条例、劳动争议解释（一）/（二）、诉讼费用交纳办法 等

clauseId 格式：{sourceKey}-第{N}条[之一]
  （如 民法典-第585条、民间借贷规定-第25条、住房租赁条例-第3条）
索引含 dense_vector(1024, cosine, index=true) 以支持 KNN（ES 8.15.5）。

注意：本脚本会删除并重建索引（全量重建，覆盖旧 595 条）。
解析：
  .docx -> zipfile 读 word/document.xml
  .doc  -> antiword -m UTF-8.txt（中文需该映射）
切分：按"第X条(+之一) 后跟全角/普通空白"的真实条款头切分。
"""
import json
import re
import subprocess
import urllib.request
import urllib.error
import urllib.parse
import zipfile

ES = "http://localhost:9200"
IDX = "rag_knowledge_base"
KB_ID = 1
ROOT = r"D:/Quanta_Back_end/back_project/fawentong/文件汇总/法规案例通-法规库"

# (sourceKey, 文件路径, 是否 .doc)
SOURCES = [
    ("民法典", f"{ROOT}/民法典/中华人民共和国民法典.docx", False),
    ("合同编通则解释", f"{ROOT}/合同类/最高人民法院关于适用《中华人民共和国民法典》合同编通则若干问题的解释.docx", False),
    ("劳动合同法", f"{ROOT}/劳动类/中华人民共和国劳动合同法.docx", False),
    ("劳动法", f"{ROOT}/劳动类/中华人民共和国劳动法.docx", False),
    ("社会保险法", f"{ROOT}/劳动类/中华人民共和国社会保险法.docx", False),
    ("劳动保障监察条例", f"{ROOT}/劳动类/劳动保障监察条例.docx", False),
    ("劳动争议解释一", f"{ROOT}/劳动类/最高人民法院关于审理劳动争议案件适用法律问题的解释（一）.docx", False),
    ("劳动争议解释二", f"{ROOT}/劳动类/最高人民法院关于审理劳动争议案件适用法律问题的解释（二）.docx", False),
    ("住房租赁条例", f"{ROOT}/租房类/住房租赁条例.docx", False),
    ("民事诉讼法", f"{ROOT}/诉讼类/中华人民共和国民事诉讼法.docx", False),
    ("诉讼费用交纳办法", f"{ROOT}/诉讼类/诉讼费用交纳办法.docx", False),
    ("民间借贷规定", f"{ROOT}/借贷类/最高人民法院关于审理民间借贷案件适用法律若干问题的规定.doc", True),
]

CN = {'零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6,
      '七': 7, '八': 8, '九': 9, '〇': 0}


def cn(s):
    if s.isdigit():
        return int(s)
    t, n = 0, 0
    for c in s:
        if c in CN:
            n = CN[c]
        elif c == '十':
            t += (n if n else 1) * 10
            n = 0
        elif c == '百':
            t += n * 100
            n = 0
        elif c == '千':
            t += n * 1000
            n = 0
    return t + n


# 真实条款头：第X条(+之一) 后跟全角空格或普通空白；排除正文中的"第X条的规定"等
ART = re.compile(r'第\s*([0-9零一二三四五六七八九十百千]+)\s*条(之一)?[\u3000\s]')


def docx_text(p):
    z = zipfile.ZipFile(p)
    xml = z.read('word/document.xml').decode('utf-8', 'ignore')
    return ''.join(re.findall(r'<w:t[^>]*>([^<]*)</w:t>', xml))


def doc_text(path):
    # antiword 是 msys 二进制，必须用 Windows 绝对路径才能被 CreateProcess 解析
    antiword = r"D:/APP/git/Git/mingw64/bin/antiword.exe"
    r = subprocess.run([antiword, '-m', 'UTF-8.txt', path],
                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return r.stdout.decode('utf-8', 'replace')


def count_items(body):
    return len(re.findall(r'（[一二三四五六七八九十百零]+）', body))


def es(method, path, body=None):
    url = ES + path
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')


MAPPING = {
    "mappings": {
        "properties": {
            "content": {"type": "text"},
            "vector": {"type": "dense_vector", "dims": 1024, "element_type": "float", "index": True, "similarity": "cosine"},
            "kbId": {"type": "long"},
            "docId": {"type": "long"},
            "userId": {"type": "long"},
            "chunkIndex": {"type": "integer"},
            "createdAt": {"type": "long"},
            "sourceKey": {"type": "keyword"},
            "articleNo": {"type": "integer"},
            "tier": {"type": "integer"},
            "canonicalId": {"type": "keyword"},
            "items": {"type": "integer"},
        }
    }
}


def es_bulk(docs, chunk=200):
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


def parse_source(source_key, path, is_doc):
    text = doc_text(path) if is_doc else docx_text(path)
    heads = list(ART.finditer(text))
    docs = []
    for i, m in enumerate(heads):
        no = cn(m.group(1))
        nxt = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        body = text[m.start():nxt].strip()
        if not body:
            continue
        suffix = m.group(2) or ""
        cid = f"{source_key}-第{no}条{suffix}"
        doc = {
            "content": body,
            "kbId": KB_ID,
            "docId": i,
            "userId": 1,
            "chunkIndex": 0,
            "createdAt": 0,
            "sourceKey": source_key,
            "articleNo": no,
            "tier": 1,
            "canonicalId": cid,
            "items": count_items(body),
        }
        docs.append({"_id": cid, "doc": doc})
    return docs


def main():
    st, _ = es("HEAD", "/" + IDX)
    if st == 200:
        es("DELETE", "/" + IDX)
        print("已删除旧索引", IDX)
    st, msg = es("PUT", "/" + IDX, MAPPING)
    print("建索引:", st, msg[:200])

    all_docs = []
    for sk, path, isdoc in SOURCES:
        try:
            ds = parse_source(sk, path, isdoc)
            all_docs += ds
            print(f"  {sk}: 收集 {len(ds)} 条")
        except Exception as e:
            import traceback
            print(f"  [ERROR] {sk}: {e}")
            traceback.print_exc()

    n_ok = es_bulk(all_docs, chunk=200)
    es("POST", f"/{IDX}/_refresh")
    st, msg = es("GET", f"/{IDX}/_count")
    print("本批写入:", n_ok)
    print("入库完成，当前文档数:", msg)

    # 校验关键 clauseId 是否存在
    for probe in ["民法典-第585条", "民法典-第680条", "民间借贷规定-第25条",
                  "劳动合同法-第1条", "住房租赁条例-第1条", "民事诉讼法-第1条"]:
        c, m = es("GET", f"/{IDX}/_doc/{urllib.parse.quote(probe)}")
        print(f"  probe {probe}: {'OK' if c == 200 else 'MISSING('+str(c)+')'}")

if __name__ == "__main__":
    main()
