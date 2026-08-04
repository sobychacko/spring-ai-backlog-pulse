import { useCallback, useEffect, useRef, useState } from 'react'
import {
  fetchBackfillStatus,
  fetchFacets,
  fetchSyncStatus,
  triggerBackfill,
  triggerCluster,
  triggerEmbed,
  triggerIngest,
  triggerIngestPrBranches,
  triggerScanDuplicates,
  triggerSync,
  type Facets,
} from './api'
import { ByFacet } from './pages/ByFacet'
import { BacklogPulse } from './pages/BacklogPulse'
import { DuplicateReview } from './pages/DuplicateReview'
import { Overview } from './pages/Overview'
import { PRReview } from './pages/PRReview'
import { Search } from './pages/Search'
import { ThemeMap } from './pages/ThemeMap'
import { ValueQueue } from './pages/ValueQueue'

type Tab = 'overview' | 'facet' | 'pulse' | 'value' | 'theme-map' | 'duplicates' | 'prs' | 'search'

const TABS: { id: Tab; label: string }[] = [
  { id: 'overview',   label: 'Overview' },
  { id: 'facet',      label: 'By Facet' },
  { id: 'pulse',      label: 'Backlog Pulse' },
  { id: 'value',      label: 'Value Queue' },
  { id: 'theme-map',  label: 'Theme Map' },
  { id: 'duplicates', label: 'Duplicate Review' },
  { id: 'prs',        label: 'PR Review' },
  { id: 'search',     label: 'Search' },
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
      setStatus(s.running ? 'Backfill running…' : (s.lastResult ?? ''))
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
      setStatus(`Found ${r.candidates} duplicate/related candidates`)
    } catch (e) {
      setStatus(`Scan error: ${e}`)
    }
  }

  async function handleIngestPrBranches() {
    setAdminOpen(false)
    setStatus('Fetching PR base branches…')
    try {
      const r = await triggerIngestPrBranches()
      setStatus(`Updated base branches for ${r.updated} PRs`)
    } catch (e) {
      setStatus(`Error: ${e}`)
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

  return (
    <div className="flex min-h-full flex-col bg-base text-body">
      {/* Header */}
      <header className="border-b border-edge px-6 py-3">
        <div className="mx-auto flex max-w-screen-xl items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <h1 className="text-[15px] font-semibold">
              Spring AI{' '}
              <span className="font-normal text-subtle">Backlog Pulse</span>
            </h1>
            {/* Tab nav */}
            <nav className="flex gap-1">
              {TABS.map((t) => (
                <button
                  key={t.id}
                  onClick={() => setTab(t.id)}
                  className={`rounded-md px-3 py-1.5 text-[13px] transition-colors ${
                    tab === t.id
                      ? 'bg-primary text-white'
                      : 'text-subtle hover:text-body hover:bg-surface'
                  }`}
                >
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
              onClick={loadFacets}
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
                    onClick={handleIngestPrBranches}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d]"
                  >
                    Fetch PR base branches
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
                    onClick={handleCluster}
                    className="block w-full px-4 py-2.5 text-left text-[13px] hover:bg-[#21262d] rounded-b-lg"
                  >
                    Build theme clusters
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      </header>

      {/* Main content */}
      <main className="flex-1 px-6 py-6">
        <div className="mx-auto max-w-screen-xl">
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
          ) : tab === 'search' ? (
            <Search />
          ) : null}
        </div>
      </main>
    </div>
  )
}
