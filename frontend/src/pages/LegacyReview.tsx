import { useEffect, useMemo, useState } from 'react'
import { fetchLegacyReview, type LegacyIssue, type LegacyReviewResponse } from '../api'

// Read-only surface (same model as Duplicates): the app points at issues that only concern
// out-of-support branches; maintainers review and close them on GitHub, and the next sync
// clears them here. PRs targeting EOL branches live in PR Review → Inactive Branches.

/** Highlight EOL version mentions (e.g. 1.0.3, 1.1.x) inside the evidence quote. */
function EvidenceQuote({ evidence, versionRegex }: { evidence: string; versionRegex: RegExp }) {
  const parts = evidence.split(versionRegex)
  const matches = evidence.match(versionRegex) ?? []
  return (
    <p className="mt-1.5 border-l-2 border-edge pl-2 text-[12px] italic text-subtle leading-relaxed">
      “
      {parts.map((part, i) => (
        <span key={i}>
          {part}
          {i < matches.length && (
            <code className="not-italic rounded bg-rose-400/10 px-1 py-0.5 text-[11px] text-rose-400">
              {matches[i]}
            </code>
          )}
        </span>
      ))}
      ”
    </p>
  )
}

function IssueCard({ issue, versionRegex }: { issue: LegacyIssue; versionRegex: RegExp }) {
  return (
    <div className="rounded-lg border border-edge bg-surface p-3.5">
      <div className="flex items-center gap-2 flex-wrap text-[11px] text-subtle">
        <span>#{issue.number}</span>
        {issue.area && <span className="text-accent">{issue.area}</span>}
        <span className="ml-auto whitespace-nowrap">
          ♥ {issue.reactions} · 💬 {issue.comments} · {issue.ageDays}d old
        </span>
      </div>
      <a
        href={issue.url}
        target="_blank"
        rel="noopener noreferrer"
        className="mt-1 block text-[13px] font-medium text-accent hover:underline leading-snug"
      >
        {issue.title}
      </a>
      {issue.evidence && <EvidenceQuote evidence={issue.evidence} versionRegex={versionRegex} />}
    </div>
  )
}

const FILTERS: { label: string; value: string; hint: string }[] = [
  {
    label: 'Legacy-only',
    value: 'LEGACY_ONLY',
    hint: 'Tied to an out-of-support release line — close candidates.',
  },
  {
    label: 'Unclear',
    value: 'UNCLEAR',
    hint: 'The AI could not tell (or its evidence quote failed the verification guard) — worth a human look.',
  },
]

export function LegacyReview() {
  const [data, setData] = useState<LegacyReviewResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [verdict, setVerdict] = useState('LEGACY_ONLY')

  useEffect(() => {
    setLoading(true)
    fetchLegacyReview(verdict)
      .then(setData)
      .catch(e => setError(String(e)))
      .finally(() => setLoading(false))
  }, [verdict])

  // e.g. eolBranches [1.0.x, 1.1.x] → /\b(1\.0|1\.1)\.[0-9x][\w.-]*/g for evidence highlighting
  const versionRegex = useMemo(() => {
    const prefixes = (data?.eolBranches ?? ['1.0.x', '1.1.x'])
      .map(b => b.replace(/\.x$/, '').replace(/\./g, '\\.'))
    return new RegExp(`\\b(?:${prefixes.join('|')})\\.[0-9x][\\w.-]*`, 'g')
  }, [data?.eolBranches])

  if (error) return <div className="rounded-lg border border-danger bg-surface p-4 text-danger">{error}</div>
  if (loading && !data) return <div className="py-24 text-center text-subtle">Loading…</div>
  if (!data) return null

  const scanned = Object.values(data.counts).reduce((a, b) => a + b, 0)
  const activeFilter = FILTERS.find(f => f.value === verdict)

  return (
    <div className="space-y-4">
      {/* Header + read-only banner */}
      <div>
        <h2 className="text-[15px] font-semibold">Legacy Review</h2>
        <p className="mt-1 text-[12px] text-subtle">
          Open issues the AI judged as only concerning an out-of-support branch (
          {data.eolBranches.map((b, i) => (
            <span key={b}>
              {i > 0 && ', '}
              <code className="rounded bg-orange-400/10 px-1 py-0.5 text-[11px] text-orange-400">{b}</code>
            </span>
          ))}
          ), each with a verbatim evidence quote — <span className="text-body">AI-suggested</span>,
          verified to occur literally in the item text. This view is{' '}
          <strong className="text-body">read-only</strong>: close resolved items on GitHub and run{' '}
          <strong className="text-body">Admin → Sync</strong> to clear them here. PRs targeting EOL
          branches are under <strong className="text-body">PR Review → Inactive Branches</strong>.
        </p>
      </div>

      {/* Verdict filter chips */}
      <div className="flex flex-wrap items-center gap-1.5">
        {FILTERS.map(f => (
          <button
            key={f.value}
            onClick={() => setVerdict(f.value)}
            title={f.hint}
            className={`rounded-md px-3 py-1 text-[12px] transition-colors ${
              verdict === f.value
                ? 'bg-primary text-white'
                : 'border border-edge text-subtle hover:text-body hover:bg-surface'
            }`}
          >
            {f.label} · {data.counts[f.value] ?? 0}
          </button>
        ))}
        <span className="ml-auto text-[12px] text-subtle">
          {scanned} scanned
          {data.pendingScan > 0 && (
            <span className="text-yellow-400"> · {data.pendingScan} pending — Admin → Scan legacy candidates</span>
          )}
        </span>
      </div>
      {activeFilter && <p className="text-[11px] text-subtle italic">{activeFilter.hint}</p>}

      {/* Issue cards */}
      {loading ? (
        <div className="py-10 text-center text-subtle">Loading…</div>
      ) : data.issues.length === 0 ? (
        <div className="py-16 text-center text-[13px] text-subtle">
          {scanned === 0 ? (
            <>
              No candidates scanned yet.
              <span className="mt-1 block text-[12px]">
                Run <strong>Admin → Scan legacy candidates</strong> to assess the{' '}
                {data.pendingScan} open issues mentioning an EOL version.
              </span>
            </>
          ) : (
            'No open issues with this verdict.'
          )}
        </div>
      ) : (
        <div className="space-y-2.5">
          {data.issues.map(issue => (
            <IssueCard key={issue.number} issue={issue} versionRegex={versionRegex} />
          ))}
        </div>
      )}
    </div>
  )
}
