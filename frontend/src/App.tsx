import { useCallback, useEffect, useRef, useState } from 'react'
import {
  fetchBackfillStatus,
  fetchFacets,
  fetchSyncStatus,
  hasAdminToken,
  setAdminToken,
  triggerBackfill,
  triggerCluster,
  triggerEmbed,
  triggerIngest,
  triggerLegacyScan,
  triggerScanDuplicates,
  triggerSonnetClassify,
  triggerSync,
  type Facets,
  triggerAdjudicate,
} from './api'
import { AskBacklog } from './pages/AskBacklog'
import { ByFacet } from './pages/ByFacet'
import { BacklogPulse } from './pages/BacklogPulse'
import { DuplicateReview } from './pages/DuplicateReview'
import { LegacyReview } from './pages/LegacyReview'
import { Overview } from './pages/Overview'
import { PRReview } from './pages/PRReview'
import { Search } from './pages/Search'
import { ThemeMap } from './pages/ThemeMap'
import { ValueQueue } from './pages/ValueQueue'

type Tab = 'overview' | 'facet' | 'pulse' | 'value' | 'theme-map' | 'duplicates' | 'prs' | 'legacy' | 'search' | 'ask'

// Per-tab accent + glyph. `active` must be literal class strings so Tailwind's JIT sees them.
const TABS: { id: Tab; label: string; icon: string; active: string }[] = [
  { id: 'overview',   label: 'Overview',         icon: '◉',
    active: 'text-sky-300 bg-sky-400/10 border-sky-400/40 shadow-[0_0_16px_-4px_rgba(56,189,248,0.6)]' },
  { id: 'facet',      label: 'By Facet',         icon: '▤',
    active: 'text-violet-300 bg-violet-400/10 border-violet-400/40 shadow-[0_0_16px_-4px_rgba(167,139,250,0.6)]' },
  { id: 'pulse',      label: 'Backlog Pulse',    icon: '⌁',
    active: 'text-amber-300 bg-amber-400/10 border-amber-400/40 shadow-[0_0_16px_-4px_rgba(251,191,36,0.6)]' },
  { id: 'value',      label: 'Value Queue',      icon: '◎',
    active: 'text-emerald-300 bg-emerald-400/10 border-emerald-400/40 shadow-[0_0_16px_-4px_rgba(52,211,153,0.6)]' },
  { id: 'theme-map',  label: 'Theme Map',        icon: '⬡',
    active: 'text-fuchsia-300 bg-fuchsia-400/10 border-fuchsia-400/40 shadow-[0_0_16px_-4px_rgba(232,121,249,0.6)]' },
  { id: 'duplicates', label: 'Duplicates', icon: '⧉',
    active: 'text-cyan-300 bg-cyan-400/10 border-cyan-400/40 shadow-[0_0_16px_-4px_rgba(34,211,238,0.6)]' },
  { id: 'prs',        label: 'PR Review',        icon: '⎇',
    active: 'text-orange-300 bg-orange-400/10 border-orange-400/40 shadow-[0_0_16px_-4px_rgba(251,146,60,0.6)]' },
  { id: 'legacy',     label: 'Legacy',           icon: '⊘',
    active: 'text-rose-300 bg-rose-400/10 border-rose-400/40 shadow-[0_0_16px_-4px_rgba(251,113,133,0.6)]' },
  { id: 'search',     label: 'Search',           icon: '⌕',
    active: 'text-indigo-300 bg-indigo-400/10 border-indigo-400/40 shadow-[0_0_16px_-4px_rgba(129,140,248,0.6)]' },
  { id: 'ask',        label: 'Ask',              icon: '✦',
    active: 'text-teal-300 bg-teal-400/10 border-teal-400/40 shadow-[0_0_16px_-4px_rgba(45,212,191,0.6)]' },
]

export default function App() {
  const [tab, setTab] = useState<Tab>('overview')
  const [facets, setFacets] = useState<Facets | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string>('')
  const [backfillRunning, setBackfillRunning] = useState(false)
  const [syncRunning, setSyncRunning] = useState(false)
  const [lastSyncedAt, setLastSyncedAt] = useState<string>('')
  const [adminOpen, setAdminOpen] = useState(false)
  // bumped by the Refresh button; remounts the active page so it refetches its own data
  const [refreshKey, setRefreshKey] = useState(0)
  const pollRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const loadFacets = useCallback(async () => {
    try {
      setError(null)
      const data = await fetchFacets()
      setFacets(data)
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  const pollStatus = useCallback(async () => {
    try {
      const s = await fetchBackfillStatus()
      setBackfillRunning(s.running)
      // 'never run' guard: older backend builds report that placeholder before any run
      const last = s.lastResult && s.lastResult !== 'never run' ? s.lastResult : ''
      setStatus(s.running ? 'Backfill running…' : last)
      if (s.running) {
        pollRef.current = setTimeout(async () => {
          await loadFacets()
          await pollStatus()
        }, 3000)
      }
    } catch {
      /* ignore */
    }
  }, [loadFacets])

  useEffect(() => {
    loadFacets()
    pollStatus()
    fetchSyncStatus().then(s => {
      setSyncRunning(s.running)
      if (s.lastRunAt) setLastSyncedAt(s.lastRunAt)
    }).catch(() => {})
    return () => {
      if (pollRef.current) clearTimeout(pollRef.current)
    }
  }, [loadFacets, pollStatus])

  async function handleIngest() {
    setAdminOpen(false)
    setStatus('Ingesting…')
    try {
      const r = await triggerIngest()
      setStatus(`Ingested ${r.ingested} items`)
      await loadFacets()
    } catch (e) {
      setStatus(`Error: ${e}`)
    }
  }

  async function handleBackfill() {
    setAdminOpen(false)
    await triggerBackfill()
    await pollStatus()
  }

  async function handleEmbed() {
    setAdminOpen(false)
    setStatus('Embedding items (local ONNX)…')
    try {
      const r = await triggerEmbed()
      setStatus(`Embedded ${r.embedded} new items (${r.total} total)`)
    } catch (e) {
      setStatus(`Embed error: ${e}`)
    }
  }

  async function handleScanDuplicates() {
    setAdminOpen(false)
    setStatus('Scanning for duplicates…')
    try {
      const r = await triggerScanDuplicates()
      setStatus(`Duplicate scan: ${r.added} new, ${r.candidates} open candidates`)
    } catch (e) {
      setStatus(`Scan error: ${e}`)
    }
  }

  async function handleSync() {
    setAdminOpen(false)
    setSyncRunning(true)
    setStatus('Syncing new/changed items…')
    try {
      await triggerSync()
      const poll = async () => {
        const s = await fetchSyncStatus()
        setSyncRunning(s.running)
        if (s.running) {
          setTimeout(poll, 2000)
        } else {
          if (s.lastRunAt) setLastSyncedAt(s.lastRunAt)
          setStatus(s.lastResult || 'Sync complete')
          await loadFacets()
        }
      }
      setTimeout(poll, 2000)
    } catch (e) {
      setSyncRunning(false)
      setStatus(`Sync error: ${e}`)
    }
  }

  async function handleCluster() {
    setAdminOpen(false)
    setStatus('Building theme clusters…')
    try {
      const r = await triggerCluster()
      setStatus(`Built ${r.clusters} theme clusters`)
    } catch (e) {
      setStatus(`Cluster error: ${e}`)
    }
  }

  async function handleAdjudicate() {
    setAdminOpen(false)
    const ok = window.confirm(
      'Adjudicate pending duplicate/competing-PR pairs with Haiku?\n' +
      'One call per pair (~$0.50 for a full backfill); re-runs only touch new pairs.',
    )
    if (!ok) return
    setStatus('Adjudicating candidate pairs…')
    try {
      const r = await triggerAdjudicate()
      setStatus(
        r.adjudicated === 0 && r.failed === 0
          ? 'Adjudication: nothing to do (all pairs have verdicts)'
          : `Adjudication: ${r.duplicate ?? 0} duplicate · ${r.related ?? 0} related · ${r.distinct ?? 0} distinct` +
            (r.failed ? ` · ${r.failed} failed` : ''),
      )
    } catch (e) {
      setStatus(`Adjudication error: ${e}`)
    }
  }

  async function handleLegacyScan() {
    setAdminOpen(false)
    const ok = window.confirm(
      'Scan open issues that mention an EOL (1.0/1.1) version with Haiku?\n' +
      '~290 candidates ≈ $0.30 one-time; re-runs only touch new/changed items.',
    )
    if (!ok) return
    setStatus('Scanning legacy candidates…')
    try {
      const r = await triggerLegacyScan()
      setStatus(
        r.scanned === 0 && r.failed === 0
          ? 'Legacy scan: nothing to do (all candidates current)'
          : `Legacy scan: ${r.legacyOnly} legacy-only · ${r.appliesToMain} apply to main · ${r.unclear} unclear` +
            (r.failed ? ` · ${r.failed} failed` : ''),
      )
    } catch (e) {
      setStatus(`Legacy scan error: ${e}`)
    }
  }

  function handleSetAdminToken() {
    setAdminOpen(false)
    const token = window.prompt(
      'Admin token (required for admin actions on a deployed instance; leave empty to clear):',
    )
    if (token == null) return
    setAdminToken(token.trim())
    setStatus(token.trim() ? 'Admin token saved (this browser only)' : 'Admin token cleared')
  }

  async function handleSonnetClassify() {
    setAdminOpen(false)
    const input = window.prompt(
      'Classify how many items with Sonnet? This is metered (~3x Haiku rates).\n' +
      '200 ≈ $2.50 comparison sample · 0 = all pending (full backlog ≈ $18)',
      '200',
    )
    if (input == null) return
    const limit = Number.parseInt(input, 10)
    if (Number.isNaN(limit) || limit < 0) {
      setStatus('Sonnet classify cancelled: invalid limit')
      return
    }
    setStatus(`Classifying ${limit === 0 ? 'all pending' : limit} item(s) with Sonnet…`)
    try {
      const r = await triggerSonnetClassify(limit)
      setStatus(`Sonnet classified ${r.classified} item(s)`)
    } catch (e) {
      setStatus(`Sonnet classify error: ${e}`)
    }
  }

  return (
    <div className="flex min-h-full flex-col bg-base text-body">
      {/* Header — projected off the page: deep blue→violet gradient, glow seam, drop shadow */}
      <header className="sticky top-0 z-40 bg-gradient-to-r from-[#0b1526]/95 via-[#111b36]/95 to-[#1a1130]/95 px-6 py-3 shadow-[0_10px_30px_-12px_rgba(76,110,245,0.35)] backdrop-blur">
        {/* accent seam along the bottom edge */}
        <div className="pointer-events-none absolute inset-x-0 bottom-0 h-px bg-gradient-to-r from-sky-500/70 via-violet-500/60 to-fuchsia-500/40" />
        <div className="mx-auto flex max-w-screen-xl items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <h1 className="flex items-center gap-2 text-[15px] font-semibold whitespace-nowrap">
              {/* live-system pulse dot */}
              <span className="relative flex h-2 w-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-400" />
              </span>
              Spring AI{' '}
              <span className="bg-gradient-to-r from-sky-300 to-violet-300 bg-clip-text font-medium text-transparent">
                Backlog Pulse
              </span>
            </h1>
            {/* Tab nav — recessed console strip, per-tab accent glow */}
            <nav className="flex gap-0.5 rounded-lg border border-edge/70 bg-black/40 p-1">
              {TABS.map((t) => (
                <button
                  key={t.id}
                  onClick={() => setTab(t.id)}
                  className={`flex items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-[12.5px] whitespace-nowrap transition-all duration-150 ${
                    tab === t.id
                      ? `font-medium ${t.active}`
                      : 'border-transparent text-subtle hover:bg-white/5 hover:text-body'
                  }`}
                >
                  <span className={`text-[11px] leading-none ${tab === t.id ? '' : 'opacity-50'}`} aria-hidden>
                    {t.icon}
                  </span>
                  {t.label}
                </button>
              ))}
            </nav>
          </div>

          <div className="flex items-center gap-3">
            {lastSyncedAt && !status && (
              <span className="text-[12px] text-subtle">
                Last sync: {new Date(lastSyncedAt).toLocaleString()}
              </span>
            )}
            {status && (
              <span className="text-[12px] text-subtle">{status}</span>
            )}
            <button
              onClick={() => { setRefreshKey(k => k + 1); loadFacets() }}
              className="rounded-md border border-edge px-3 py-1.5 text-[13px] text-subtle hover:text-body hover:bg-surface transition-colors"
            >
              Refresh
            </button>
            {/* Admin dropdown */}
            <div className="relative">
              <button
                onClick={() => setAdminOpen((o) => !o)}
                className="rounded-md border border-edge px-3 py-1.5 text-[13px] text-subtle hover:text-body hover:bg-surface transition-colors"
              >
                Admin ▾
              </button>
              {adminOpen && (
                <div className="absolute right-0 top-full mt-1 w-56 rounded-lg border border-edge bg-surface shadow-lg z-50">
                  <button
                    onClick={handleIngest}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d] rounded-t-lg"
                  >
                    Ingest only
                  </button>
                  <button
                    onClick={handleBackfill}
                    disabled={backfillRunning}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d] disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    Backfill (ingest + classify)
                  </button>
                  <button
                    onClick={handleSync}
                    disabled={syncRunning}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d] disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {syncRunning ? 'Syncing…' : 'Sync (incremental)'}
                  </button>
                  <button
                    onClick={handleSonnetClassify}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d]"
                  >
                    Classify with Sonnet
                  </button>
                  <div className="my-1 border-t border-edge" />
                  <button
                    onClick={handleEmbed}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d]"
                  >
                    Embed items (local ONNX)
                  </button>
                  <button
                    onClick={handleScanDuplicates}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d]"
                  >
                    Scan for duplicates
                  </button>
                  <button
                    onClick={handleAdjudicate}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d]"
                  >
                    Adjudicate duplicate pairs
                  </button>
                  <button
                    onClick={handleCluster}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d]"
                  >
                    Build theme clusters
                  </button>
                  <button
                    onClick={handleLegacyScan}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d]"
                  >
                    Scan legacy candidates
                  </button>
                  <div className="my-1 border-t border-edge" />
                  <button
                    onClick={handleSetAdminToken}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d] rounded-b-lg text-subtle"
                  >
                    {hasAdminToken() ? 'Change admin token…' : 'Set admin token…'}
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      </header>

      {/* Main content — keyed by refreshKey so Refresh remounts (and refetches) the active page */}
      <main className="flex-1 px-6 py-6">
        <div className="mx-auto max-w-screen-xl" key={refreshKey}>
          {loading ? (
            <div className="flex items-center justify-center py-24 text-subtle">
              Loading…
            </div>
          ) : error ? (
            <div className="rounded-lg border border-danger bg-surface p-4 text-danger">
              {error}
            </div>
          ) : facets && tab === 'overview' ? (
            <Overview facets={facets} />
          ) : facets && tab === 'facet' ? (
            <ByFacet facets={facets} />
          ) : tab === 'pulse' ? (
            <BacklogPulse />
          ) : tab === 'value' ? (
            <ValueQueue />
          ) : tab === 'theme-map' ? (
            <ThemeMap />
          ) : tab === 'duplicates' ? (
            <DuplicateReview />
          ) : tab === 'prs' ? (
            <PRReview />
          ) : tab === 'legacy' ? (
            <LegacyReview />
          ) : tab === 'search' ? (
            <Search />
          ) : tab === 'ask' ? (
            <AskBacklog />
          ) : null}
        </div>
      </main>
    </div>
  )
}
