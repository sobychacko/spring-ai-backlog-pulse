import { useEffect, useRef, useState } from 'react'
import { fetchItems, type ItemView } from '../api'
import { ItemDrawer } from '../components/ItemDrawer'

const TYPES = ['BUG', 'ENHANCEMENT', 'QUESTION', 'DOCUMENTATION', 'TASK']
const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
const AREAS = [
  'core', 'chat-client', 'chat-model', 'embedding', 'vector-store', 'rag',
  'tool-calling', 'mcp', 'observability', 'chat-memory', 'structured-output',
  'agents', 'advisors', 'auto-config', 'docs', 'testing', 'build', 'other',
]

const SEVERITY_CLS: Record<string, string> = {
  CRITICAL: 'text-danger border-danger',
  HIGH:     'text-warn border-warn',
  MEDIUM:   'text-subtle border-subtle',
  LOW:      'text-subtle border-subtle',
}
const TYPE_CLS: Record<string, string> = {
  BUG:           'bg-red-400/10 text-red-400',
  ENHANCEMENT:   'bg-blue-400/10 text-blue-400',
  QUESTION:      'bg-purple-400/10 text-purple-400',
  DOCUMENTATION: 'bg-teal-400/10 text-teal-400',
  TASK:          'bg-surface text-subtle',
}

function Chip({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`rounded-full px-2.5 py-1 text-[12px] border transition-colors ${
        active
          ? 'bg-primary border-primary text-white'
          : 'border-edge text-subtle hover:text-body hover:border-subtle'
      }`}
    >
      {label}
    </button>
  )
}

export function Search({ model }: { model?: string } = {}) {
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState<'issue' | 'pr' | ''>('')
  const [type, setType] = useState('')
  const [area, setArea] = useState('')
  const [severity, setSeverity] = useState('')
  const [results, setResults] = useState<ItemView[]>([])
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [drawer, setDrawer] = useState<ItemView[] | null>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const hasFilters = !!query || !!kind || !!type || !!area || !!severity

  useEffect(() => {
    if (!hasFilters) {
      setResults([])
      setSearched(false)
      return
    }
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      setLoading(true)
      fetchItems({
        search: query || undefined,
        kind: kind || undefined,
        type: type || undefined,
        area: area || undefined,
        severity: severity || undefined,
        limit: 200,
        model,
      })
        .then(r => { setResults(r); setSearched(true) })
        .catch(() => {})
        .finally(() => setLoading(false))
    }, 300)
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current) }
  }, [query, kind, type, area, severity, hasFilters, model])

  function clear() {
    setQuery(''); setKind(''); setType(''); setArea(''); setSeverity('')
    setResults([]); setSearched(false)
  }

  return (
    <div className="space-y-4">
      {/* Search box */}
      <div className="relative">
        <span className="absolute left-3 top-1/2 -translate-y-1/2 text-subtle text-[14px]">⌕</span>
        <input
          autoFocus
          type="text"
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="Search titles and summaries…"
          className="w-full rounded-lg border border-edge bg-surface pl-8 pr-10 py-2.5 text-[14px] placeholder:text-subtle focus:outline-none focus:border-primary"
        />
        {hasFilters && (
          <button
            onClick={clear}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-subtle hover:text-body text-[16px]"
          >×</button>
        )}
      </div>

      {/* Filter chips */}
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-subtle uppercase tracking-wide w-14">Kind</span>
          <Chip label="Issue" active={kind === 'issue'} onClick={() => setKind(k => k === 'issue' ? '' : 'issue')} />
          <Chip label="PR"    active={kind === 'pr'}    onClick={() => setKind(k => k === 'pr'    ? '' : 'pr')} />
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-subtle uppercase tracking-wide w-14">Type</span>
          {TYPES.map(t => (
            <Chip key={t} label={t} active={type === t} onClick={() => setType(v => v === t ? '' : t)} />
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-subtle uppercase tracking-wide w-14">Severity</span>
          {SEVERITIES.map(s => (
            <Chip key={s} label={s} active={severity === s} onClick={() => setSeverity(v => v === s ? '' : s)} />
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-subtle uppercase tracking-wide w-14">Area</span>
          {AREAS.map(a => (
            <Chip key={a} label={a} active={area === a} onClick={() => setArea(v => v === a ? '' : a)} />
          ))}
        </div>
      </div>

      {/* Results */}
      {loading && <div className="py-8 text-center text-subtle text-[13px]">Searching…</div>}

      {!loading && searched && (
        <div className="rounded-lg border border-edge bg-surface overflow-hidden">
          <div className="px-4 py-2.5 border-b border-edge flex items-center justify-between">
            <span className="text-[13px] text-subtle">
              {results.length === 0 ? 'No results' : `${results.length} result${results.length === 1 ? '' : 's'}`}
              {results.length === 200 && <span className="ml-1 text-[11px]">(capped at 200)</span>}
            </span>
          </div>
          {results.length === 0 ? (
            <div className="py-12 text-center text-subtle text-[13px]">No items match your search.</div>
          ) : (
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-edge text-[11px] text-subtle uppercase tracking-wide">
                  <th className="px-3 py-2">#</th>
                  <th className="px-3 py-2">Kind</th>
                  <th className="px-3 py-2">Title</th>
                  <th className="px-3 py-2">Type</th>
                  <th className="px-3 py-2">Area</th>
                  <th className="px-3 py-2">Severity</th>
                  <th className="px-3 py-2">👍+💬</th>
                </tr>
              </thead>
              <tbody>
                {results.map(item => (
                  <tr
                    key={item.number}
                    className="border-b border-edge hover:bg-surface/50 cursor-pointer"
                    onClick={() => setDrawer([item])}
                  >
                    <td className="px-3 py-2.5 text-[12px] text-subtle">{item.number}</td>
                    <td className="px-3 py-2.5 text-[11px] text-subtle">{item.kind === 'pr' ? '⇄ PR' : '◎ Issue'}</td>
                    <td className="px-3 py-2.5 text-[13px] max-w-md">
                      <span className="line-clamp-1 font-medium">{item.title}</span>
                      {item.summary && <p className="text-[11px] text-subtle mt-0.5 line-clamp-1">{item.summary}</p>}
                    </td>
                    <td className="px-3 py-2.5 text-[11px]">
                      {item.type && (
                        <span className={`rounded px-1.5 py-0.5 ${TYPE_CLS[item.type] ?? 'text-subtle'}`}>
                          {item.type}
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2.5 text-[12px] text-subtle">{item.area ?? '—'}</td>
                    <td className="px-3 py-2.5 text-[12px]">
                      {item.severity && (
                        <span className={`text-[11px] border rounded px-1 ${SEVERITY_CLS[item.severity] ?? 'text-subtle border-edge'}`}>
                          {item.severity}
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2.5 text-[12px] text-subtle tabular-nums">
                      {item.reactions + item.comments}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      {!loading && !searched && (
        <div className="py-16 text-center text-subtle text-[13px]">
          Type a keyword or pick a filter above to search the backlog.
        </div>
      )}

      {drawer && (
        <ItemDrawer
          title="Item detail"
          items={drawer}
          onClose={() => setDrawer(null)}
        />
      )}
    </div>
  )
}
