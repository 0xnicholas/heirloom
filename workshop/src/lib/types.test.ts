import { describe, it, expect } from 'vitest';
import { ABILITIES, FIELD_TYPES, RELATIONSHIP_SEMANTICS } from './constants';

describe('constants', () => {
  it('ABILITIES should have 8 items', () => {
    expect(ABILITIES).toHaveLength(8);
  });

  it('FIELD_TYPES should include all primitive types', () => {
    expect(FIELD_TYPES).toContain('string');
    expect(FIELD_TYPES).toContain('number');
    expect(FIELD_TYPES).toContain('rid');
  });

  it('RELATIONSHIP_SEMANTICS should have 3 items', () => {
    expect(RELATIONSHIP_SEMANTICS).toHaveLength(3);
    expect(RELATIONSHIP_SEMANTICS).toContain('Ownership');
    expect(RELATIONSHIP_SEMANTICS).toContain('Reference');
    expect(RELATIONSHIP_SEMANTICS).toContain('Association');
  });
});
