import { useEffect, useState } from 'react'
import { fetchValue, type ValueItem } from '../api'

function ageDays(days: number): string {
  if (days < 1) return 'today'
  if (days < 7) return `${days}d ago`
  if (days < 30) return `${Math.round(days / 7)}w ago`
  if (days < 365) return `${Math.round(days / 30)}mo ago`
  return `${(days / 365).toFixed(1)}y ago`
}

const SEVERITY_COLOR: Record<string, string> = {
  CRITICAL: 'border-danger text-danger',
  HIGH:     'border-warn text-warn',
  MEDIUM:   'border-subtle text-subtle',
  LOW:      'border-edge text-subtle',
}

const TYPE_COLOR: Record<string, string> = {
  BUG:           'border-danger text-danger',
  ENHANCEMENT:   'border-accent text-accent',
  DOCUMENTATION: 'border-subtle text-subtle',
  QUESTION:      'border-purple text-purple',
  TASK:          'border-subtle text-subtle',
}

function Tag({ label, colorClass }: { label: string; colorClass?: string }) {
  return (
    <span
      className={`inline-block rounded-full border px-2 py-0.5 text-[11px] ${colorClass ?? 'border-edge text-subtle'}`}
    >
      {label}
    </span>
  )
}

function ScorePip({ score }: { score: number }) {
  const color =
    score >= 70 ? 'bg-danger' : score >= 45 ? 'bg-warn' : 'bg-primary'
  return (
    <div className="flex items-center gap-1.5 shrink-0">
      <div className={`h-2 w-2 rounded-full ${color}`} />
      <span className="text-[12px] tabular-nums text-subtle">{score}</span>
    </div>
  )
}

function ValueCard({ item, rank }: { item: ValueItem; rank: number }) {
  return (
    <div className="rounded-lg border border-edge bg-surface p-4 hover:border-[#58a6ff33] transition-colors">
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2 text-[12px] text-subtle shrink-0 flex-wrap">
          <span className="w-5 text-right tabular-nums">{rank}.</span>
          <Tag label={`#${item.number}`} />
          <Tag label={item.kind} />
          {item.duplicateCount > 0 && (
            <span
              title="Possible duplicates found — see Duplicate Review tab"
              className="inline-block rounded-full border border-warn px-1.5 py-0.5 text-[10px] text-warn cursor-help"
            >
              ⚠ {item.duplicateCount} dup{item.duplicateCount > 1 ? 's' : ''}
            </span>
          )}
        </div>
        <ScorePip score={item.valueScore} />
      </div>

      <a
        href={item.url}
        target="_blank"
        rel="noopener noreferrer"
        className="mt-2 block text-[14px] font-medium text-accent hover:underline leading-snug"
      >
        {item.title}
      </a>

      {item.summary && (
        <p className="mt-1.5 text-[13px] text-subtle leading-relaxed line-clamp-2">
          {item.summary}{' '}
          <span className="rounded-full border border-[#6e40c9] px-1.5 py-0.5 text-[10px] text-purple ml-1">AI</span>
        </p>
      )}

      <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
        {item.type && <Tag label={item.type} colorClass={TYPE_COLOR[item.type]} />}
        {item.area && <Tag label={item.area} colorClass="border-primary text-accent" />}
        {item.severity && item.severity !== 'LOW' && (
          <Tag label={item.severity} colorClass={SEVERITY_COLOR[item.severity]} />
        )}
        {(item.providers ?? []).map((p) => (
          <Tag key={p} label={p} />
        ))}
        {item.goodFirstIssue && (
          <Tag label="good first issue" colorClass="border-success text-success" />
        )}
      </div>

      <div className="mt-2.5 flex items-center gap-3 text-[12px] text-subtle">
        <span>👍 {item.reactions}</span>
        <span>💬 {item.comments}</span>
        <span>🕐 {ageDays(item.ageDays)}</span>
      </div>
    </div>
  )
}

export function ValueQueue({ model }: { model?: string } = {}) {
  const [items, setItems] = useState<ValueItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    fetchValue(25, model)
      .then(setItems)
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false))
  }, [model])

  if (loading) return <div className="py-16 text-center text-subtle">Loading…</div>
  if (error) return <div className="rounded-lg border border-danger bg-surface p-4 text-danger">{error}</div>

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-[15px] font-semibold">Where to Add Value</h2>
        <p className="mt-1 text-[12px] text-subtle">
          Top open issues ranked by value score: 50% engagement · 30% age · 10% severity · 10% good-first-issue.
          All scores are GitHub facts. Titles, summaries, and tags are{' '}
          <span className="rounded-full border border-[#6e40c9] px-1.5 py-0.5 text-[10px] text-purple">AI-suggested</span>
          {' '}and should be verified.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        {items.map((item, i) => (
          <ValueCard key={item.number} item={item} rank={i + 1} />
        ))}
      </div>

      {items.length === 0 && (
        <p className="py-12 text-center text-subtle">No classified issues yet — run backfill first.</p>
      )}
    </div>
  )
}
