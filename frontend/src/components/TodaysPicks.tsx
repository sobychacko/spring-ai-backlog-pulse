import { useCallback, useEffect, useState } from 'react'
import { decidePick, fetchPicks, type PickView, type PicksResponse } from '../api'

// The morning list: high-value open issues the AI judged landable on main in about an hour,
// with no API break and nothing blocking. Ordering is the GitHub-derived value score; every
// verdict field is AI-suggested and carries the verbatim quote it rests on. "Took it" and
// "Skip" are the maintainer's calls — they remove the card and stop re-assessment.

const EFFORT_LABEL: Record<PickView['effort'], string> = {
  ABOUT_AN_HOUR: '~1 hour',
  HALF_DAY: 'half a day',
  MULTI_DAY: 'multi-day',
  CANNOT_TELL: 'unclear',
}

const EFFORT_CLASS: Record<PickView['effort'], string> = {
  ABOUT_AN_HOUR: 'bg-emerald-400/10 text-emerald-300',
  HALF_DAY: 'bg-amber-400/10 text-amber-300',
  MULTI_DAY: 'bg-rose-400/10 text-rose-300',
  CANNOT_TELL: 'bg-edge text-subtle',
}

const API_LABEL: Record<PickView['apiRisk'], string> = {
  NONE: 'no API change',
  ADDITIVE: 'additive API',
  BREAKING: 'breaking API',
  CANNOT_TELL: 'API impact unclear',
}

const BLOCKER_LABEL: Record<string, string> = {
  NEEDS_REPORTER_INFO: 'needs reporter info',
  NEEDS_DESIGN_DECISION: 'needs design decision',
  NEEDS_EXTERNAL_CHANGE: 'needs external change',
  LIKELY_ALREADY_FIXED: 'likely already fixed',
  SOMEONE_WORKING_ON_IT: 'someone is on it',
  NOT_ACTIONABLE: 'not actionable',
}

function Chip({ className, children, title }: { className: string; children: React.ReactNode; title?: string }) {
  return (
    <span title={title} className={`rounded px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide ${className}`}>
      {children}
    </span>
  )
}

function PickCard({
  pick,
  onDecide,
  busy,
}: {
  pick: PickView
  onDecide: (number: number, decision: 'TAKEN' | 'SKIPPED' | 'NONE') => void
  busy: boolean
}) {
  const decided = pick.decision != null
  return (
    <div className={`rounded-lg border border-edge bg-surface p-3.5 ${decided ? 'opacity-70' : ''}`}>
      <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-subtle">
        <span>#{pick.number}</span>
        {pick.area && <span className="text-accent">{pick.area}</span>}
        {pick.type && <span>{pick.type.toLowerCase()}</span>}
        <Chip className={EFFORT_CLASS[pick.effort]} title="AI-estimated effort for a maintainer">
          {EFFORT_LABEL[pick.effort]}
        </Chip>
        <Chip
          className={pick.apiRisk === 'BREAKING' ? 'bg-rose-400/10 text-rose-300' : 'bg-edge text-subtle'}
          title="AI-estimated public API impact"
        >
          {API_LABEL[pick.apiRisk]}
        </Chip>
        {pick.confidence && pick.confidence !== 'HIGH' && (
          <Chip className="bg-edge text-subtle" title="How well the text supports the verdict">
            {pick.confidence.toLowerCase()} confidence
          </Chip>
        )}
        {pick.blockers.map(b => (
          <Chip key={b} className="bg-amber-400/10 text-amber-300" title="AI-detected blocker">
            {BLOCKER_LABEL[b] ?? b.toLowerCase()}
          </Chip>
        ))}
        <span className="ml-auto whitespace-nowrap">
          value {pick.valueScore} · ♥ {pick.reactions} · 💬 {pick.comments} · {pick.ageDays}d old
        </span>
      </div>
      <a
        href={pick.url}
        target="_blank"
        rel="noopener noreferrer"
        className="mt-1 block text-[13px] font-medium text-accent hover:underline leading-snug"
      >
        {pick.title}
      </a>
      {pick.summary && <p className="mt-1 text-[12px] text-body leading-relaxed">{pick.summary}</p>}
      {pick.evidence && (
        <p className="mt-1.5 border-l-2 border-edge pl-2 text-[12px] italic text-subtle leading-relaxed">
          “{pick.evidence}”
        </p>
      )}
      {(pick.firstStep || pick.likelyScope) && (
        <p className="mt-1.5 text-[12px] text-body leading-relaxed">
          {pick.likelyScope && (
            <>
              <span className="text-subtle">Scope: </span>
              <code className="rounded bg-edge px-1 py-0.5 text-[11px]">{pick.likelyScope}</code>
              {pick.firstStep && ' · '}
            </>
          )}
          {pick.firstStep && (
            <>
              <span className="text-subtle">First step: </span>
              {pick.firstStep}
            </>
          )}
        </p>
      )}
      <div className="mt-2 flex items-center gap-2 text-[12px]">
        {decided ? (
          <>
            <span className="text-subtle">
              {pick.decision === 'TAKEN' ? '✓ Took it' : '✕ Skipped'}
              {pick.decidedAt && ` · ${new Date(pick.decidedAt).toLocaleDateString()}`}
            </span>
            <button
              disabled={busy}
              onClick={() => onDecide(pick.number, 'NONE')}
              className="rounded-md border border-edge px-2 py-0.5 text-subtle hover:bg-[#21262d] disabled:opacity-50"
            >
              Undo
            </button>
          </>
        ) : (
          <>
            <button
              disabled={busy}
              onClick={() => onDecide(pick.number, 'TAKEN')}
              className="rounded-md border border-emerald-400/40 px-2.5 py-0.5 text-emerald-300 hover:bg-emerald-400/10 disabled:opacity-50"
            >
              ✓ Took it
            </button>
            <button
              disabled={busy}
              onClick={() => onDecide(pick.number, 'SKIPPED')}
              className="rounded-md border border-edge px-2.5 py-0.5 text-subtle hover:bg-[#21262d] disabled:opacity-50"
            >
              ✕ Skip
            </button>
            <span className="ml-auto text-[11px] text-subtle" title="Assessed by">
              {pick.modelUsed}
            </span>
          </>
        )}
      </div>
    </div>
  )
}

export function TodaysPicks() {
  const [data, setData] = useState<PicksResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [includeHalfDay, setIncludeHalfDay] = useState(false)
  const [showAll, setShowAll] = useState(false)
  const [showDecided, setShowDecided] = useState(false)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      setError(null)
      const efforts = includeHalfDay ? ['ABOUT_AN_HOUR', 'HALF_DAY'] : ['ABOUT_AN_HOUR']
      setData(await fetchPicks(efforts, 100))
    } catch (e) {
      setError(String(e))
    }
  }, [includeHalfDay])

  useEffect(() => {
    load()
  }, [load])

  async function onDecide(number: number, decision: 'TAKEN' | 'SKIPPED' | 'NONE') {
    setBusy(true)
    try {
      await decidePick(number, decision)
      await load()
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  const counts = data?.counts ?? {}
  const assessedTotal = Object.values(counts).reduce((s, n) => s + n, 0)
  const readyCount = data?.picks.length ?? 0
  // about-an-hour verdicts that are not ready: blocked, breaking, or otherwise held back
  const heldBack = (counts['ABOUT_AN_HOUR'] ?? 0) - (data?.picks.filter(p => p.effort === 'ABOUT_AN_HOUR').length ?? 0)
  const list = showAll ? (data?.assessed ?? []) : (data?.picks ?? [])

  return (
    <div className="rounded-lg border border-edge bg-surface p-4">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 className="text-[15px] font-semibold">Today's picks</h2>
        <p className="text-[12px] text-subtle">
          High-value open issues a maintainer could land on <span className="text-body">main</span> in about an
          hour — unassigned, no open PR, no API break, nothing blocking. Effort and blockers are{' '}
          <span className="text-body">AI-suggested</span>; the order is the GitHub-derived value score.
        </p>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[12px]">
        <button
          onClick={() => { setShowAll(false) }}
          className={`rounded-md px-3 py-1 transition-colors ${!showAll ? 'bg-emerald-400/10 text-emerald-300' : 'text-subtle hover:bg-[#21262d]'}`}
        >
          Ready to take {data ? `(${readyCount})` : ''}
        </button>
        <button
          onClick={() => { setShowAll(true) }}
          className={`rounded-md px-3 py-1 transition-colors ${showAll ? 'bg-emerald-400/10 text-emerald-300' : 'text-subtle hover:bg-[#21262d]'}`}
        >
          All assessed {data ? `(${assessedTotal})` : ''}
        </button>
        {!showAll && (
          <label className="ml-1 flex cursor-pointer items-center gap-1.5 text-subtle">
            <input
              type="checkbox"
              checked={includeHalfDay}
              onChange={e => setIncludeHalfDay(e.target.checked)}
              className="accent-emerald-400"
            />
            include half-day
          </label>
        )}
        <span className="ml-auto text-[11px] text-subtle">
          {data?.lastAssessedAt
            ? `assessed ${new Date(data.lastAssessedAt).toLocaleString()} · ${data.model}`
            : data ? 'never assessed' : ''}
          {data && data.pendingAssessment > 0 && (
            <span className="text-yellow-400"> · {data.pendingAssessment} pending — Admin → Assess today's picks</span>
          )}
        </span>
      </div>

      {error && <div className="mt-3 rounded-lg border border-danger p-3 text-[12px] text-danger">{error}</div>}

      {!data && !error ? (
        <div className="py-8 text-center text-[13px] text-subtle">Loading…</div>
      ) : list.length === 0 ? (
        <div className="py-8 text-center text-[13px] text-subtle">
          {assessedTotal === 0
            ? 'No assessments yet. Run Admin → Assess today\'s picks (or wait for the daily pipeline).'
            : showAll
              ? 'Nothing assessed is still eligible.'
              : heldBack > 0
                ? `Nothing ready to take — ${heldBack} about-an-hour ${heldBack === 1 ? 'issue is' : 'issues are'} blocked (see "All assessed"), or try "include half-day".`
                : 'Nothing ready to take right now — try "include half-day" or "All assessed".'}
        </div>
      ) : (
        <div className="mt-3 grid grid-cols-1 gap-2.5 lg:grid-cols-2">
          {list.map(p => (
            <PickCard key={p.number} pick={p} onDecide={onDecide} busy={busy} />
          ))}
        </div>
      )}

      {data && data.decided.length > 0 && (
        <div className="mt-3">
          <button
            onClick={() => setShowDecided(v => !v)}
            className="text-[12px] text-subtle hover:text-body"
          >
            {showDecided ? '▾' : '▸'} Recently decided ({data.decided.length})
          </button>
          {showDecided && (
            <div className="mt-2 grid grid-cols-1 gap-2.5 lg:grid-cols-2">
              {data.decided.map(p => (
                <PickCard key={p.number} pick={p} onDecide={onDecide} busy={busy} />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
