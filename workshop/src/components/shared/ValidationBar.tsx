import type { Diagnostic } from '@/lib/types';

interface ValidationBarProps {
  diagnostics: Diagnostic[];
}

export function ValidationBar({ diagnostics }: ValidationBarProps) {
  if (diagnostics.length === 0) {
    return (
      <div className="flex items-center gap-2 px-3 py-1.5 bg-green-50 border border-green-200 rounded text-sm text-green-700">
        <span className="text-green-500">✓</span>
        <span>All validations pass</span>
      </div>
    );
  }

  const errors = diagnostics.filter(d => d.severity === 'error');
  const warnings = diagnostics.filter(d => d.severity === 'warning');

  return (
    <div className="flex flex-col gap-1">
      <div
        className={`flex items-center gap-2 px-3 py-1.5 rounded text-sm ${
          errors.length > 0
            ? 'bg-red-50 border border-red-200 text-red-700'
            : 'bg-yellow-50 border border-yellow-200 text-yellow-700'
        }`}
      >
        <span>{errors.length > 0 ? '✗' : '⚠'}</span>
        <span>
          {errors.length > 0 ? `${errors.length} error${errors.length > 1 ? 's' : ''}` : ''}
          {errors.length > 0 && warnings.length > 0 ? ', ' : ''}
          {warnings.length > 0 ? `${warnings.length} warning${warnings.length > 1 ? 's' : ''}` : ''}
        </span>
      </div>
      {diagnostics.map((d, i) => (
        <div
          key={i}
          className={`text-xs px-3 ${
            d.severity === 'error' ? 'text-red-600' : d.severity === 'warning' ? 'text-yellow-600' : 'text-blue-600'
          }`}
        >
          {d.message}
        </div>
      ))}
    </div>
  );
}
