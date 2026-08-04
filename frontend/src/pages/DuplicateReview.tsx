import { useEffect, useState } from 'react'
import {
  confirmDuplicate,
  dismissDuplicate,
  fetchDuplicates,
  type DuplicateItemDetail,
  type DuplicatePair,
} from '../api'

const TYPE_LABELS: Record<string, { label: string; color: string; hint: string }> = {
  duplicate_candidate: {
    label: 'Possible duplicate',
    color: 'border-warn text-warn',
    hint: 'Two issues about the same bug or request. Confirming marks the relationship — close one manually after review.',
  },
  competing_pr: {
    label: 'Competing PRs',
    color: 'border-accent text-accent',
    hint: 'Two pull requests working on the same thing. Confirming notes the overlap for reviewers.',
  },
  pr_fixes_issue: {
    label: 'PR may fix issue',
    color: 'border-success text-success',
    hint: 'The PR appears to address this issue. Confirming links them — do NOT close the issue, it will close when the PR merges.',
  },
  related: {
    label: 'Related',
    color: 'border-subtle text-subtle',
    hint: 'Semantically similar but without a direct fix relationship.',
  },
}

const KIND_ICON: Record<string, string> = {
  issue: '◎',
  pr: '⇄',
}

function ItemPanel({ item }: { item: DuplicateItemDetail }) {
  return (
    <div className="flex-1 min-w-0 rounded-lg border border-edge bg-[#0d1117] p-3">
      <div className="flex items-center gap-1.5 text-[11px] text-subtle mb-1">
        <span>{KIND_ICON[item.kind] ?? '○'}</span>
        <span className="uppercase tracking-wide">{item.kind}</span>
        <span>·</span>
        <span>#{item.number}</span>
        {item.area && (
          <>
            <span>·</span>
            <span className="text-accent">{item.area}</span>
          </>
        )}
      </div>
      <a
        href={item.url}
        target="_blank"
        rel="noopener noreferrer"
        className="block text-[13px] font-medium text-accent hover:underline leading-snug line-clamp-2"
      >
        {item.title}
      </a>
      {item.summary && (
        <p className="mt-1.5 text-[12px] text-subtle leading-relaxed line-clamp-3">{item.summary}</p>
      )}
    </div>
  )
}

function PairCard({
  pair,
  onAction,
}: {
  pair: DuplicatePair
  onAction: (id: number, action: 'confirm' | 'dismiss') => void
}) {
  const [busy, setBusy] = useState(false)
  const meta = TYPE_LABELS[pair.type] ?? { label: pair.type, color: 'border-edge text-subtle', hint: '' }
  const pct = Math.round(pair.confidence * 100)

  async function handle(action: 'confirm' | 'dismiss') {
    setBusy(true)
    try {
      if (action === 'confirm') await confirmDuplicate(pair.id)
      else await dismissDuplicate(pair.id)
      onAction(pair.id, action)
    } catch (e) {
      console.error(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="rounded-lg border border-edge bg-surface p-4 space-y-3">
      {/* Header row */}
      <div className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`rounded-full border px-2 py-0.5 text-[11px] ${meta.color}`}>{meta.label}</span>
          <span className="rounded-full border border-edge px-2 py-0.5 text-[11px] text-subtle">
            {pct}% similarity
          </span>
          <span className="rounded-full border border-edge px-2 py-0.5 text-[11px] text-subtle capitalize">
            {pair.source === 'embedding' ? '⊕ embedding' : '⊙ GH ref'}
          </span>
        </div>
        <div className="flex gap-2 shrink-0">
          <button
            onClick={() => handle('dismiss')}
            disabled={busy}
            className="rounded-md border border-edge px-2.5 py-1 text-[12px] text-subtle hover:text-body hover:bg-[#21262d] transition-colors disabled:opacity-50"
          >
            Dismiss
          </button>
          <button
            onClick={() => handle('confirm')}
            disabled={busy}
            className="rounded-md bg-primary px-2.5 py-1 text-[12px] text-white hover:opacity-90 transition-opacity disabled:opacity-50"
          >
            Confirm
          </button>
        </div>
      </div>

      {/* Hint */}
      {meta.hint && (
        <p className="text-[11px] text-subtle italic border-l-2 border-edge pl-2">{meta.hint}</p>
      )}

      {/* Side-by-side item panels */}
      <div className="flex gap-3">
        <ItemPanel item={pair.from} />
        <div className="flex items-center text-subtle text-lg shrink-0">↔</div>
        <ItemPanel item={pair.to} />
      </div>
    </div>
  )
}

const FILTERS = [
  { label: 'All', value: '' },
  { label: 'Issue ↔ Issue', value: 'duplicate_candidate' },
  { label: 'PR ↔ Issue', value: 'pr_fixes_issue' },
  { label: 'Competing PRs', value: 'competing_pr' },
  { label: 'Related', value: 'related' },
]

export function DuplicateReview() {
  const [pairs, setPairs] = useState<DuplicatePair[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [offset, setOffset] = useState(0)
  const LIMIT = 20

  useEffect(() => {
    setLoading(true)
    fetchDuplicates({ type: filter || undefined, limit: LIMIT, offset })
      .then((r) => {
        setPairs(r.pairs)
        setTotal(r.total)
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false))
  }, [filter, offset])

  function handleAction(id: number) {
    setPairs((prev) => prev.filter((p) => p.id !== id))
    setTotal((t) => Math.max(0, t - 1))
  }

  function handleFilter(f: string) {
    setFilter(f)
    setOffset(0)
  }

  if (error) return <div className="rounded-lg border border-danger bg-surface p-4 text-danger">{error}</div>

  return (
    <div className="space-y-4">
      {/* Header */}
      <div>
        <h2 className="text-[15px] font-semibold">Duplicate Review</h2>
        <p className="mt-1 text-[12px] text-subtle">
          Embedding-based candidates grouped by relationship type. Confirm or dismiss each pair.
          Confirming a <em>PR fixes issue</em> pair only links them in the database — it does
          <strong className="text-body"> not</strong> close the issue on GitHub.
        </p>
      </div>

      {/* Filter tabs */}
      <div className="flex flex-wrap gap-1.5">
        {FILTERS.map((f) => (
          <button
            key={f.value}
            onClick={() => handleFilter(f.value)}
            className={`rounded-md px-3 py-1 text-[12px] transition-colors ${
              filter === f.value
                ? 'bg-primary text-white'
                : 'border border-edge text-subtle hover:text-body hover:bg-surface'
            }`}
          >
            {f.label}
          </button>
        ))}
        <span className="ml-auto self-center text-[12px] text-subtle">{total} pending</span>
      </div>

      {/* Pairs */}
      {loading ? (
        <div className="py-16 text-center text-subtle">Loading…</div>
      ) : pairs.length === 0 ? (
        <div className="py-16 text-center text-subtle">
          No pending candidates.
          {total === 0 && (
            <span className="block mt-1 text-[12px]">
              Run <strong>Admin → Embed items</strong> then <strong>Scan for duplicates</strong> to populate.
            </span>
          )}
        </div>
      ) : (
        <div className="space-y-3">
          {pairs.map((p) => (
            <PairCard key={p.id} pair={p} onAction={handleAction} />
          ))}
        </div>
      )}

      {/* Pagination */}
      {total > LIMIT && (
        <div className="flex items-center justify-center gap-3 pt-2">
          <button
            onClick={() => setOffset(Math.max(0, offset - LIMIT))}
            disabled={offset === 0}
            className="rounded-md border border-edge px-3 py-1.5 text-[13px] text-subtle hover:text-body hover:bg-surface disabled:opacity-40"
          >
            ← Prev
          </button>
          <span className="text-[12px] text-subtle">
            {offset + 1}–{Math.min(offset + LIMIT, total)} of {total}
          </span>
          <button
            onClick={() => setOffset(offset + LIMIT)}
            disabled={offset + LIMIT >= total}
            className="rounded-md border border-edge px-3 py-1.5 text-[13px] text-subtle hover:text-body hover:bg-surface disabled:opacity-40"
          >
            Next →
          </button>
        </div>
      )}
    </div>
  )
}
