interface Props {
  label: string
  value: number | string
  sub?: string
  color?: 'default' | 'accent' | 'success' | 'warn' | 'danger' | 'purple'
  onClick?: () => void
}

const colorClass: Record<NonNullable<Props['color']>, string> = {
  default: 'text-body',
  accent:  'text-accent',
  success: 'text-success',
  warn:    'text-warn',
  danger:  'text-danger',
  purple:  'text-purple',
}

export function StatCard({ label, value, sub, color = 'default', onClick }: Props) {
  return (
    <div
      className={`rounded-lg border border-edge bg-surface p-4 ${onClick ? 'cursor-pointer hover:border-[#58a6ff55] hover:bg-[#1c2128] transition-colors' : ''}`}
      onClick={onClick}
    >
      <div className={`text-2xl font-bold tabular-nums ${colorClass[color]}`}>
        {value}
      </div>
      <div className="mt-1 text-[11px] uppercase tracking-wider text-subtle">{label}</div>
      {sub && <div className="mt-0.5 text-[11px] text-subtle">{sub}</div>}
    </div>
  )
}
