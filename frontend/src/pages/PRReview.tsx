import { useEffect, useState } from 'react'
import { fetchPRs, type PrView } from '../api'

const ACTIVE_BRANCH = /^(main|2\.\d.*)$/
const DEFAULT_VISIBLE = 15

function isOldBranch(b: string | null): boolean {
  if (!b) return false
  return !ACTIVE_BRANCH.test(b)
}

function complexity(c: string | null) {
  if (c === 'LOW')    return { label: 'Easy',   cls: 'text-emerald-400 bg-emerald-400/10', order: 0 }
  if (c === 'MEDIUM') return { label: 'Medium', cls: 'text-yellow-400 bg-yellow-400/10',   order: 1 }
  if (c === 'HIGH')   return { label: 'Hard',   cls: 'text-red-400 bg-red-400/10',         order: 2 }
  return null
}

function mainApplicable(v: string | null) {
  if (v === 'YES')     return { label: '✓ Applies to main', cls: 'text-emerald-400' }
  if (v === 'NO')      return { label: '✗ Old-branch only', cls: 'text-red-400' }
  if (v === 'UNCLEAR') return { label: '? Unclear',         cls: 'text-yellow-400' }
  return null
}

function PrRow({ pr }: { pr: PrView }) {
  const cx = complexity(pr.reviewComplexity)
  const ma = mainApplicable(pr.mainBranchApplicable)
  return (
    <tr className="border-b border-edge hover:bg-surface/50">
      <td className="px-3 py-2.5 text-[12px] text-subtle whitespace-nowrap">
        <a href={pr.url} target="_blank" rel="noreferrer" className="hover:text-primary">#{pr.number}</a>
      </td>
      <td className="px-3 py-2.5 text-[13px] max-w-sm">
        <a href={pr.url} target="_blank" rel="noreferrer" className="hover:text-primary line-clamp-1 font-medium">
          {pr.title}
        </a>
        {pr.summary && <p className="text-[11px] text-subtle mt-0.5 line-clamp-1">{pr.summary}</p>}
      </td>
      <td className="px-3 py-2.5 text-[12px] text-subtle whitespace-nowrap">{pr.area ?? '—'}</td>
      <td className="px-3 py-2.5 text-[12px] whitespace-nowrap">
        {cx && <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${cx.cls}`}>{cx.label}</span>}
      </td>
      <td className="px-3 py-2.5 text-[12px]">
        {pr.reviewNotes
          ? <span className="text-subtle">{pr.reviewNotes}</span>
          : pr.mainBranchNote
            ? <span className="text-subtle">{pr.mainBranchNote}</span>
            : '—'}
      </td>
      <td className="px-3 py-2.5 text-[12px] whitespace-nowrap">
        {pr.baseBranch ? (
          <code className={`text-[11px] px-1.5 py-0.5 rounded ${isOldBranch(pr.baseBranch) ? 'bg-orange-400/10 text-orange-400' : 'bg-surface text-subtle'}`}>
            {pr.baseBranch}
          </code>
        ) : '—'}
        {ma && <span className={`ml-1 text-[11px] ${ma.cls}`}>{ma.label}</span>}
      </td>
      <td className="px-3 py-2.5 text-[12px] text-subtle whitespace-nowrap">{pr.author}</td>
      <td className="px-3 py-2.5 text-[12px] text-subtle whitespace-nowrap">{pr.daysSinceUpdate}d ago</td>
    </tr>
  )
}

type SortOrder = 'hard-first' | 'easy-first'
type SectionKey = 'easy' | 'old-branch' | 'community' | 'stale'

function PrTable({ prs, sortOrder, emptyMsg }: { prs: PrView[]; sortOrder: SortOrder; emptyMsg: string }) {
  const [expanded, setExpanded] = useState(false)

  const sorted = [...prs].sort((a, b) => {
    const ao = complexity(a.reviewComplexity)?.order ?? 1
    const bo = complexity(b.reviewComplexity)?.order ?? 1
    if (ao !== bo) return sortOrder === 'hard-first' ? bo - ao : ao - bo
    return a.daysSinceUpdate - b.daysSinceUpdate
  })

  const visible = expanded ? sorted : sorted.slice(0, DEFAULT_VISIBLE)

  if (prs.length === 0) {
    return <div className="py-16 text-center text-subtle text-[13px]">{emptyMsg}</div>
  }

  return (
    <div>
      <div className="overflow-x-auto">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-edge text-[11px] text-subtle uppercase tracking-wide">
              <th className="px-3 py-2">#</th>
              <th className="px-3 py-2">Title</th>
              <th className="px-3 py-2">Area</th>
              <th className="px-3 py-2">Complexity</th>
              <th className="px-3 py-2">Notes</th>
              <th className="px-3 py-2">Branch</th>
              <th className="px-3 py-2">Author</th>
              <th className="px-3 py-2">Updated</th>
            </tr>
          </thead>
          <tbody>
            {visible.map(pr => <PrRow key={pr.number} pr={pr} />)}
          </tbody>
        </table>
      </div>
      {sorted.length > DEFAULT_VISIBLE && (
        <button
          onClick={() => setExpanded(e => !e)}
          className="w-full px-4 py-2.5 text-[12px] text-subtle hover:text-body hover:bg-[#21262d] border-t border-edge text-center transition-colors"
        >
          {expanded ? 'Show less' : `Show all ${sorted.length}`}
        </button>
      )}
    </div>
  )
}

export function PRReview() {
  const [prs, setPrs] = useState<PrView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<SectionKey>('easy')
  const [sortOrder, setSortOrder] = useState<SortOrder>('hard-first')

  useEffect(() => {
    fetchPRs()
      .then(setPrs)
      .catch(e => setError(String(e)))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="py-24 text-center text-subtle">Loading…</div>
  if (error) return <div className="rounded-lg border border-danger bg-surface p-4 text-danger">{error}</div>

  const nonDraft = prs.filter(p => !p.draft)

  const sections: Record<SectionKey, { label: string; accent: string; emptyMsg: string; prs: PrView[] }> = {
    'easy': {
      label: 'Easy to Review',
      accent: 'emerald',
      emptyMsg: 'No low-complexity PRs — run Admin → Classify to populate review complexity.',
      prs: nonDraft
        .filter(p => p.reviewComplexity === 'LOW')
        .sort((a, b) => (b.reactions + b.comments) - (a.reactions + a.comments)),
    },
    'old-branch': {
      label: 'Inactive Branches',
      accent: 'orange',
      emptyMsg: 'No PRs targeting inactive branches.',
      prs: nonDraft
        .filter(p => isOldBranch(p.baseBranch))
        .sort((a, b) => a.daysSinceUpdate - b.daysSinceUpdate),
    },
    'community': {
      label: 'Awaiting Review',
      accent: 'blue',
      emptyMsg: 'No community PRs awaiting a first review.',
      prs: nonDraft
        .filter(p => p.comments === 0 &&
          ['FIRST_TIME_CONTRIBUTOR', 'NONE', 'CONTRIBUTOR'].includes(p.authorAssoc ?? ''))
        .sort((a, b) => a.daysSinceUpdate - b.daysSinceUpdate),
    },
    'stale': {
      label: 'Stale',
      accent: 'yellow',
      emptyMsg: 'No stale PRs (nothing untouched for 30+ days).',
      prs: nonDraft
        .filter(p => p.daysSinceUpdate > 30)
        .sort((a, b) => b.daysSinceUpdate - a.daysSinceUpdate),
    },
  }

  const accentBorder: Record<string, string> = {
    emerald: 'border-emerald-500 text-emerald-400',
    orange:  'border-orange-500 text-orange-400',
    blue:    'border-blue-500 text-blue-400',
    yellow:  'border-yellow-500 text-yellow-400',
  }
  const accentBadge: Record<string, string> = {
    emerald: 'bg-emerald-400/10 text-emerald-400',
    orange:  'bg-orange-400/10 text-orange-400',
    blue:    'bg-blue-400/10 text-blue-400',
    yellow:  'bg-yellow-400/10 text-yellow-400',
  }

  const active = sections[activeTab]

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-[16px] font-semibold">PR Review</h1>
          <p className="text-[12px] text-subtle mt-0.5">{nonDraft.length} open PRs · excluding drafts</p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-[12px] text-subtle">Sort by complexity:</span>
          <div className="flex rounded-md border border-edge overflow-hidden text-[12px]">
            <button
              onClick={() => setSortOrder('hard-first')}
              className={`px-3 py-1.5 transition-colors ${sortOrder === 'hard-first' ? 'bg-primary text-white' : 'text-subtle hover:text-body hover:bg-surface'}`}
            >
              Hard first
            </button>
            <button
              onClick={() => setSortOrder('easy-first')}
              className={`px-3 py-1.5 border-l border-edge transition-colors ${sortOrder === 'easy-first' ? 'bg-primary text-white' : 'text-subtle hover:text-body hover:bg-surface'}`}
            >
              Easy first
            </button>
          </div>
        </div>
      </div>

      {/* Section tabs */}
      <div className="grid grid-cols-4 gap-3">
        {(Object.entries(sections) as [SectionKey, typeof sections[SectionKey]][]).map(([key, s]) => (
          <button
            key={key}
            onClick={() => setActiveTab(key)}
            className={`rounded-lg border p-4 text-left transition-all ${
              activeTab === key
                ? `border-${s.accent}-500 bg-${s.accent}-400/5`
                : 'border-edge bg-surface hover:border-subtle'
            }`}
          >
            <div className="flex items-start justify-between gap-2">
              <span className={`text-[13px] font-medium ${activeTab === key ? accentBorder[s.accent].split(' ')[1] : 'text-body'}`}>
                {s.label}
              </span>
              <span className={`rounded px-1.5 py-0.5 text-[12px] font-semibold tabular-nums ${
                activeTab === key ? accentBadge[s.accent] : 'bg-surface text-subtle'
              }`}>
                {s.prs.length}
              </span>
            </div>
            {activeTab === key && (
              <div className={`mt-2 h-0.5 w-full rounded ${`bg-${s.accent}-500`}`} />
            )}
          </button>
        ))}
      </div>

      {/* Active section table */}
      <div className="rounded-lg border border-edge bg-surface overflow-hidden">
        <PrTable prs={active.prs} sortOrder={sortOrder} emptyMsg={active.emptyMsg} />
      </div>
    </div>
  )
}
