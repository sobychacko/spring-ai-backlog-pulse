import { useEffect, useState } from 'react'
import {
  decideDuplicate,
  fetchDuplicates,
  hasAdminToken,
  type DuplicateItemDetail,
  type DuplicatePair,
} from '../api'

// Read-only surface: the app proposes pairs, GitHub is where they get resolved.
// Closing/merging either item clears the pair here on the next sync.
const TYPE_LABELS: Record<string, { label: string; color: string; hint: string }> = {
  duplicate_candidate: {
    label: 'Possible duplicate',
    color: 'border-warn text-warn',
    hint: 'Two issues about the same bug or request. If truly duplicates, close one on GitHub — the pair disappears here after the next sync.',
  },
  competing_pr: {
    label: 'Competing PRs',
    color: 'border-accent text-accent',
    hint: 'Two pull requests working on the same thing — worth knowing before reviewing either. Clears when one is closed or merged.',
  },
  pr_fixes_issue: {
    label: 'PR may fix issue',
    color: 'border-success text-success',
    hint: 'The PR appears to address this issue. When the PR merges and closes the issue, the pair clears on its own.',
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

const VERDICT_META: Record<string, { label: string; color: string }> = {
  DUPLICATE: { label: 'AI: duplicate', color: 'border-danger text-danger' },
  RELATED: { label: 'AI: related', color: 'border-subtle text-subtle' },
  DISTINCT: { label: 'AI: distinct', color: 'border-success text-success' },
}

function PairCard({ pair, onDecided }: { pair: DuplicatePair; onDecided: (id: number) => void }) {
  const meta = TYPE_LABELS[pair.type] ?? { label: pair.type, color: 'border-edge text-subtle', hint: '' }
  const pct = Math.round(pair.confidence * 100)
  const verdict = pair.verdict ? VERDICT_META[pair.verdict] : null
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function decide(confirmed: boolean) {
    setBusy(true)
    setError(null)
    try {
      await decideDuplicate(pair.id, confirmed)
      onDecided(pair.id)
    } catch (e) {
      setError(String(e))
      setBusy(false)
    }
  }

  return (
    <div className="rounded-lg border border-edge bg-surface p-4 space-y-3">
      {/* Header row */}
      <div className="flex items-center gap-2 flex-wrap">
        <span className={`rounded-full border px-2 py-0.5 text-[11px] ${meta.color}`}>{meta.label}</span>
        <span className="rounded-full border border-edge px-2 py-0.5 text-[11px] text-subtle">
          {pct}% similarity
        </span>
        <span className="rounded-full border border-edge px-2 py-0.5 text-[11px] text-subtle capitalize">
          {pair.source === 'embedding' ? '⊕ embedding' : '⊙ GH ref'}
        </span>
        {verdict && (
          <span className={`rounded-full border px-2 py-0.5 text-[11px] ${verdict.color}`}>
            {verdict.label}
          </span>
        )}
        {hasAdminToken() && (
          <span className="ml-auto flex gap-1.5">
            <button
              onClick={() => decide(true)}
              disabled={busy}
              className="rounded-md border border-success/60 px-2.5 py-0.5 text-[11px] text-success hover:bg-success/10 disabled:opacity-40"
              title="Mark as a real duplicate/relationship (removes from this list)"
            >
              ✓ Confirm
            </button>
            <button
              onClick={() => decide(false)}
              disabled={busy}
              className="rounded-md border border-edge px-2.5 py-0.5 text-[11px] text-subtle hover:text-body hover:bg-[#21262d] disabled:opacity-40"
              title="Not a duplicate — dismiss permanently"
            >
              ✕ Dismiss
            </button>
          </span>
        )}
      </div>

      {/* AI verdict rationale */}
      {pair.verdictRationale && (
        <p className="text-[11px] text-subtle italic border-l-2 border-edge pl-2">
          AI-suggested: {pair.verdictRationale}
        </p>
      )}

      {error && <p className="text-[11px] text-danger">{error}</p>}

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

  function handleFilter(f: string) {
    setFilter(f)
    setOffset(0)
  }

  if (error) return <div className="rounded-lg border border-danger bg-surface p-4 text-danger">{error}</div>

  return (
    <div className="space-y-4">
      {/* Header */}
      <div>
        <h2 className="text-[15px] font-semibold">Duplicates</h2>
        <p className="mt-1 text-[12px] text-subtle">
          AI-suggested candidate pairs, grouped by relationship type — only pairs where both
          items are still open. Resolve true duplicates on GitHub (close one of the issues) and
          run <strong className="text-body">Admin → Sync</strong> to clear resolved pairs; with
          the admin token set you can also <strong className="text-body">Confirm</strong> a pair
          or <strong className="text-body">Dismiss</strong> a false positive here. Nothing ever
          writes to GitHub.
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
        <span className="ml-auto self-center text-[12px] text-subtle">{total} candidates</span>
      </div>

      {/* Pairs */}
      {loading ? (
        <div className="py-16 text-center text-subtle">Loading…</div>
      ) : pairs.length === 0 ? (
        <div className="py-16 text-center text-subtle">
          No open candidates.
          {total === 0 && (
            <span className="block mt-1 text-[12px]">
              Run <strong>Admin → Embed items</strong> then <strong>Scan for duplicates</strong> to populate.
            </span>
          )}
        </div>
      ) : (
        <div className="space-y-3">
          {pairs.map((p) => (
            <PairCard
              key={p.id}
              pair={p}
              onDecided={(id) => {
                setPairs((prev) => prev.filter((x) => x.id !== id))
                setTotal((t) => t - 1)
              }}
            />
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
