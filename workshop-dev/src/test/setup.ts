import '@testing-library/jest-dom';
import { vi } from 'vitest';

// Mock ReactFlow for jsdom — no browser layout APIs
vi.mock('@xyflow/react', async () => {
  const actual = await vi.importActual('@xyflow/react');
  return {
    ...actual,
    ReactFlow: () => null,
  };
});
