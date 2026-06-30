import type { ReactNode } from 'react';

interface SpecimenProps {
  children: ReactNode;
  annotation?: string;
  className?: string;
}

export function Specimen({ children, annotation, className = '' }: SpecimenProps) {
  return (
    <div
      className={`rounded-xl border border-stone-200 bg-white shadow-sm overflow-hidden ${className}`}
    >
      <div className="flex items-center gap-2 px-4 py-3 border-b border-stone-100 bg-stone-50/80">
        <span className="w-3 h-3 rounded-full bg-red-400/80" />
        <span className="w-3 h-3 rounded-full bg-amber-400/80" />
        <span className="w-3 h-3 rounded-full bg-emerald-400/80" />
        <span className="ml-auto text-xs font-mono text-stone-400">specimen.heirloom</span>
      </div>
      <div className="p-5">
        {children}
        {annotation && (
          <p className="mt-4 text-xs font-mono text-stone-400 leading-relaxed">
            {annotation}
          </p>
        )}
      </div>
    </div>
  );
}
