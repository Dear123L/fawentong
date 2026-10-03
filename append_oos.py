import json

PATH = "contract_qa_testset.json"

with open(PATH, encoding="utf-8") as f:
    d = json.load(f)

oos = [
    {
        "id": "OOS001",
        "type": "out_of_scope",
        "question": "我和老公要离婚了，婚内买的房子和存款该怎么分？他出轨算不算过错方能少分点？",
        "expected_answer": "应拒答：知识库仅覆盖合同/商事条款（生效、付款违约金、不可抗力、保密、定金、违约），本问题属婚姻法/离婚财产分割域，无对应条款，应明确说明无法作答、不编造。",
        "expected_clause_ids": [],
        "calculation_params": None,
        "reject_reason": "6 条语料均为商事合同通用约定，不含婚姻法/夫妻共同财产分割、过错方认定规则，完全无依据。",
    },
    {
        "id": "OOS002",
        "type": "out_of_scope",
        "question": "我们公司想改章程把注册资本从100万降到50万，股东会要多少比例通过才合法？",
        "expected_answer": "应拒答：知识库未覆盖公司法/公司章程修改事项，仅含买卖合同类约定，应明确说明无法作答、不编造。",
        "expected_clause_ids": [],
        "calculation_params": None,
        "reject_reason": "KB 无公司法、公司章程、股东会表决比例相关内容，属公司组织法域，超出范围。",
    },
    {
        "id": "OOS003",
        "type": "out_of_scope",
        "question": "我今年养了个娃在上幼儿园，个税专项附加扣除能扣多少？租房那块还能一起扣吗？",
        "expected_answer": "应拒答：知识库未含税法/个人所得税内容，应明确说明无法作答、不编造。",
        "expected_clause_ids": [],
        "calculation_params": None,
        "reject_reason": "KB 仅含合同违约与价款类条款，无个人所得税、专项附加扣除规则，属税法域。",
    },
    {
        "id": "OOS004",
        "type": "out_of_scope",
        "question": "公司试用期第2个月就无缘无故把我开了，也没提前说，能要赔偿不？试用期到底能不能随便辞退？",
        "expected_answer": "应拒答：知识库仅覆盖一般合同（买卖/保密/定金/违约），不含劳动合同法下的试用期、违法解除赔偿，应明确说明无法作答、不编造。",
        "expected_clause_ids": [],
        "calculation_params": None,
        "reject_reason": "KB 无劳动法/试用期/违法解除劳动合同赔偿条款，属劳动法律域，无依据。",
    },
    {
        "id": "OOS005",
        "type": "out_of_scope",
        "question": "有人仿我们牌子卖假货，商标被侵权了我能索赔多少？损失到底怎么算？",
        "expected_answer": "应拒答：知识库未含知识产权/商标法条，应明确说明无法作答、不编造。",
        "expected_clause_ids": [],
        "calculation_params": None,
        "reject_reason": "KB 无商标权、侵权赔偿计算相关内容，属商标法/知识产权域，超出范围。",
    },
    {
        "id": "OOS006",
        "type": "out_of_scope",
        "question": "我爸去世没留遗嘱，名下房子和存款我和我姐怎么分？他后娶的继母有份吗？",
        "expected_answer": "应拒答：知识库未含继承法/遗嘱继承规则，应明确说明无法作答、不编造。",
        "expected_clause_ids": [],
        "calculation_params": None,
        "reject_reason": "KB 无法定继承、遗嘱、继承人范围相关内容，属继承法域，无依据。",
    },
    {
        "id": "OOS007",
        "type": "out_of_scope",
        "question": "我开车被后车追尾了，交警判对方全责，修车钱和误工费能让他赔多少？走保险还是直接找人？",
        "expected_answer": "应拒答：知识库未含道路交通安全法/侵权损害赔偿内容，应明确说明无法作答、不编造。",
        "expected_clause_ids": [],
        "calculation_params": None,
        "reject_reason": "KB 无交通事故责任认定、人身/财产损害赔偿规则，属侵权/交通事故域，超出范围。",
    },
    {
        "id": "OOS008",
        "type": "out_of_scope",
        "question": "我在网上买到假名牌包，能要求退一赔三不？商家说只肯退货不退钱，这合法吗？",
        "expected_answer": "应拒答：知识库未含消费者权益保护法/惩罚性赔偿条款，应明确说明无法作答、不编造。",
        "expected_clause_ids": [],
        "calculation_params": None,
        "reject_reason": "KB 无消法、欺诈惩罚性赔偿（退一赔三）相关内容，属消费者权益保护域，超出范围。",
    },
]

existing_ids = {t["id"] for t in d["data"]}
for o in oos:
    assert o["id"] not in existing_ids, f"dup id {o['id']}"
    d["data"].append(o)

# 同步 meta 计数
c = d["meta"]["counts"]
c["out_of_scope"] = len(oos)
c["total"] = len(d["data"])

with open(PATH, "w", encoding="utf-8") as f:
    json.dump(d, f, ensure_ascii=False, indent=2)

print("OK appended", len(oos), "OOS items; total now", len(d["data"]))
print("counts:", d["meta"]["counts"])
