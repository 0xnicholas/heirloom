import { createTheme, type MantineColorsTuple } from '@mantine/core';

// Indigo palette — matches website/ marketing site primary
const indigo: MantineColorsTuple = [
  '#eef2ff', '#e0e7ff', '#c7d2fe', '#a5b4fc', '#818cf8',
  '#6366f1', '#4f46e5', '#4338ca', '#3730a3', '#312e81',
];

// Stone palette — neutral grays for surfaces/text
const stone: MantineColorsTuple = [
  '#fafaf9', '#f5f5f4', '#e7e5e4', '#d6d3d1', '#a8a29e',
  '#78716c', '#57534e', '#44403c', '#292524', '#1c1917',
];

export const theme = createTheme({
  primaryColor: 'indigo',
  primaryShade: { light: 6, dark: 5 },
  fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  defaultRadius: 'md',
  colors: { indigo, stone },
});
