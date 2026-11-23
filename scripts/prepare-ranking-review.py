import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def build():
    fixture = json.loads((ROOT / 'src/test/resources/retrieval-metrics-v1.json').read_text(encoding='utf-8'))
    gold = {q['questionId']: q for q in json.loads((ROOT / 'docs/evaluation/m4-01-gold-evidence.json').read_text(encoding='utf-8'))}
    paths = (ROOT / 'docs/evaluation/m4-01-samples.txt').read_text(encoding='utf-8').splitlines()
    pools = []
    for q in gold:
        for chunking in ('FIXED_CODE_POINT', 'MARKDOWN_STRUCTURE_AWARE'):
            runs = [r for r in fixture['runs'] if r['questionId'] == q and r['chunking'] == chunking]
            snapshots = {r['snapshotHash'] for r in runs}
            assert len(snapshots) == 1
            candidates = {}
            for run in runs:
                for c in run['chunks']:
                    key = (c['documentId'], c['indexVersionId'], c['chunkId'])
                    if key not in candidates:
                        base = 800000 if chunking == 'FIXED_CODE_POINT' else 900000
                        path = paths[c['documentId'] - base - 100]
                        source = ROOT / 'evaluation' / 'corpus' / path.removeprefix('../')
                        raw = source.read_bytes() if source.exists() else None
                        label = next(j for j in fixture['pool']['judgments'] if j['questionId'] == q and j['chunking'] == chunking and j['chunkId'] == c['chunkId'])
                        candidates[key] = dict(chunkId=c['chunkId'], documentId=c['documentId'], indexVersionId=c['indexVersionId'], documentPath=path,
                            sourceSha256=hashlib.sha256(raw).hexdigest() if raw else None, sourceStart=c['sourceStart'], sourceEnd=c['sourceEnd'],
                            content=c['content'], contentSha256=hashlib.sha256(c['content'].encode()).hexdigest(),
                            materialStatus='FIXTURE_CONTENT_AVAILABLE', relevance=label['relevance'], reviewStatus='UNJUDGED', proposedRelevance=None, reviewer=None, rationale=None, hits=[])
                    assert candidates[key]['content'] == c['content']
                    candidates[key]['hits'].append(dict(strategy=run['strategy'], rank=c['finalRank']))
            pools.append(dict(poolId=fixture['pool']['id'] + '/' + q + '/' + chunking, poolVersion=1, status='PENDING', dataset=fixture['protocol'], source=fixture['source'], snapshotHash=next(iter(snapshots)), questionId=q, question=gold[q]['question'], expectedAnswerPoints=gold[q]['expectedAnswerPoints'], chunking=chunking, candidates=list(candidates.values())))
    assert len(fixture['runs']) == 72 and sum(len(r['chunks']) for r in fixture['runs']) == 360
    assert sum(len(p['candidates']) for p in pools) == len(fixture['pool']['judgments']) == 186
    return dict(instructions='人工结合答案要点评审：3 DIRECT，2 SUPPORTING，1 TOPICAL，0 IRRELEVANT；不得依据来源重叠或检索分数自动评分。proposedRelevance/rationale/reviewer 待人工填写；全部完成方可冻结。新增池外候选必须新建版本。来源范围为零基 Unicode 码点左闭右开。', pools=pools)

if __name__ == '__main__':
    output = ROOT / 'docs/evaluation/m4-b03-review-materials.json'
    rendered = json.dumps(build(), ensure_ascii=False, indent=2) + '\n'
    import sys
    if '--check' in sys.argv:
        assert output.read_text(encoding='utf-8') == rendered
    else:
        output.write_text(rendered, encoding='utf-8')
