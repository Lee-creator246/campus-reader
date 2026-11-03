"""Offline evidence-selection scoring. Never opens a network connection or reads an API key.

Input JSONL: {"id":"volunteer","output":{...schema 2 response...}} for every public case.
Scores exact paragraph choices against a small adjudicated synthetic set, NOT general model accuracy.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASES = ROOT / 'app/src/test/resources/ai-eval-cases.jsonl'
FIELDS = {'purpose', 'audience', 'actions', 'deadline', 'location', 'materials', 'conditions'}


def load(path):
    rows = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row['id'] in rows:
            raise ValueError('Duplicate case ID')
        rows[row['id']] = row
    return rows


def valid(output, fixture):
    if not isinstance(output, dict) or set(output) != {'schema', 'fields', 'tags', 'policy_links'} or output['schema'] != 2:
        return False
    if not isinstance(output['fields'], dict) or set(output['fields']) != FIELDS:
        return False
    units = {f'N{i}' for i in range(1, 2 + len([line for line in fixture['body'].splitlines() if line.strip()]))}
    for refs in output['fields'].values():
        if not isinstance(refs, list) or len(refs) > 6 or any(not isinstance(ref, str) or ref not in units for ref in refs):
            return False
    if not isinstance(output['tags'], list) or len(output['tags']) > 3 or output['policy_links'] != []:
        return False
    for tag in output['tags']:
        if not isinstance(tag, dict) or set(tag) != {'name', 'evidence'} or not isinstance(tag['name'], str):
            return False
        if not isinstance(tag['evidence'], list) or not 1 <= len(tag['evidence']) <= 3 or any(not isinstance(ref, str) or ref not in units for ref in tag['evidence']):
            return False
    return True


def score(cases, predictions):
    if set(cases) != set(predictions):
        raise ValueError('Predictions must contain exactly all fixture IDs; missing/extra cases are not silently ignored')
    totals = dict(expected=0, selected=0, correct=0, tag_expected=0, tag_selected=0, tag_correct=0,
                  valid_structure=0, forbidden_selected=0, empty_fields=0, wrong_nonempty_fields=0)
    details = []
    for ident, case in cases.items():
        output = predictions[ident].get('output')
        structure = valid(output, case)
        totals['valid_structure'] += int(structure)
        row = {'id': ident, 'valid_structure': structure, 'missing': {}, 'extra': {}}
        for field in sorted(FIELDS):
            expected = set(case['fields'][field])
            actual = set(output['fields'][field]) if structure else set()
            totals['expected'] += len(expected)
            totals['selected'] += len(actual)
            totals['correct'] += len(actual & expected)
            totals['forbidden_selected'] += len(actual & set(case.get('forbidden', {}).get(field, [])))
            if not expected:
                totals['empty_fields'] += 1
                totals['wrong_nonempty_fields'] += int(bool(actual))
            if expected - actual:
                row['missing'][field] = sorted(expected - actual)
            if actual - expected:
                row['extra'][field] = sorted(actual - expected)
        tags = {tag['name'] for tag in output['tags']} if structure else set()
        expected_tags = set(case['tags'])
        totals['tag_expected'] += len(expected_tags)
        totals['tag_selected'] += len(tags)
        totals['tag_correct'] += len(tags & expected_tags)
        details.append(row)
    def ratio(a, b):
        return round(a / b, 4) if b else None
    return {'scope': '12 synthetic text-only cases; paragraph selection, not general accuracy or policy-retrieval evaluation',
            'network_called': False, 'cases': len(cases), 'counts': totals,
            'evidence_precision': ratio(totals['correct'], totals['selected']),
            'required_evidence_recall': ratio(totals['correct'], totals['expected']),
            'tag_precision': ratio(totals['tag_correct'], totals['tag_selected']),
            'tag_recall': ratio(totals['tag_correct'], totals['tag_expected']), 'details': details}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--predictions', type=Path)
    parser.add_argument('--self-test', action='store_true', help='Test the scorer with artificial perfect/incorrect outputs; not a model run')
    args = parser.parse_args()
    cases = load(CASES)
    if args.self_test:
        predictions = {ident: {'output': {'schema': 2, 'fields': case['fields'],
                       'tags': [{'name': tag, 'evidence': ['N1']} for tag in case['tags']], 'policy_links': []}}
                       for ident, case in cases.items()}
        result = score(cases, predictions)
        assert result['required_evidence_recall'] == 1 and result['evidence_precision'] == 1
        predictions['publication_only']['output']['fields'] = dict(cases['publication_only']['fields'], deadline=['N2'])
        damaged = score(cases, predictions)
        assert damaged['counts']['forbidden_selected'] == 1 and damaged['evidence_precision'] < 1
        predictions['publication_only']['output']['schema'] = 3
        assert score(cases, predictions)['counts']['valid_structure'] == len(cases) - 1
        print('Offline scorer self-test passed; no model was called or evaluated.')
    elif args.predictions:
        print(json.dumps(score(cases, load(args.predictions)), ensure_ascii=False, indent=2))
    else:
        parser.error('Provide --predictions or --self-test')


if __name__ == '__main__':
    main()
