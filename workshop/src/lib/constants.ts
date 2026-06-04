import type { Ability, FieldType, RelationshipSemantics } from './types';

export const ABILITIES: Ability[] = ['key', 'store', 'query', 'mutate', 'transfer', 'copy', 'drop', 'freeze'];

export const FIELD_TYPES: FieldType[] = ['string', 'number', 'boolean', 'enum', 'date', 'rid'];

export const RELATIONSHIP_SEMANTICS: RelationshipSemantics[] = ['Ownership', 'Reference', 'Association'];

export const AGGREGATE_FNS = ['$count', '$sum', '$avg', '$max', '$min'] as const;

export const QUERY_TEMPLATES = {
  basic: `{
  "from": "",
  "select": [],
  "limit": 50
}`,
  traverse: `{
  "from": "",
  "alias": "a",
  "traverse": [{
    "path": "a --[]--> ",
    "alias": "b"
  }],
  "select": [],
  "limit": 50
}`,
  aggregate: `{
  "from": "",
  "aggregate": {
    "fn": "$count",
    "group_by": []
  }
}`,
  search: `{
  "from": "",
  "search": {
    "type": "hybrid",
    "query": "",
    "properties": [],
    "min_score": 0.7
  },
  "select": [],
  "limit": 10
}`,
} as const;
