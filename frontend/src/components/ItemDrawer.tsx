import { useEffect, useRef, useState } from 'react'
import { fetchItems, fetchSemanticSearch, type ItemView, type SemanticHit } from '../api'

const KIND_ICON: Record<string, string> = { issue: '◎', pr: '⇄' }

const SEVERITY_COLOR: Record<string, string> = {
  CRITICAL: 'text-danger border-danger',
  HIGH:     'text-warn border-warn',
  MEDIUM:   'text-subtle border-subtle',
}

interface Props {
  title: string
  subtitle?: string
  /** Pass either pre-loaded items or filter params — not both */
  items?: ItemView[]
  filter?: Parameters<typeof fetchItems>[0]
  onClose: () => void
}

export function ItemDrawer({ title, subtitle, items: preloaded, filter, onClose }: Props) {
  const [items, setItems] = useState<ItemView[]>(preloaded ?? [])
  const [loading, setLoading] = useState(!preloaded)
  // null = loading / not applicable; [] = no neighbors above threshold
  const [similar, setSimilar] = useState<SemanticHit[] | null>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (preloaded) return
    fetchItems({ limit: 60, ...filter })
      .then(setItems)
      .finally(() => setLoading(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Single-item detail view: surface semantic nearest neighbors — the item's title+summary
  // is the query, matched by meaning against the whole backlog (vector similarity).
  const single = !loading && items.length === 1 ? items[0] : null
  useEffect(() => {
    if (!single) {
      setSimilar(null)
      return
    }
    const q = `${single.title}. ${single.summary ?? ''}`.trim()
    fetchSemanticSearch({ q, limit: 8 })
      .then(hits => setSimilar(hits.filter(h => h.number !== single.number).slice(0, 5)))
      .catch(() => setSimilar([]))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [single?.number])

  // Lock background scroll
  useEffect(() => {
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = '' }
  }, [])

  // ESC to close
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  useEffect(() => { panelRef.current?.focus() }, [])

  return (
    <>
      <div className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm" onClick={onClose} />

      <div
        ref={panelRef}
        tabIndex={-1}
        className="fixed right-0 top-0 z-50 flex h-full w-full max-w-[540px] flex-col border-l border-edge bg-base shadow-2xl outline-none"
        style={{ animation: 'slideIn 180ms ease-out' }}
      >
        {/* Header */}
        <div className="flex shrink-0 items-start gap-3 border-b border-edge px-5 py-4">
          <div className="flex-1 min-w-0">
            <h3 className="text-[15px] font-semibold leading-snug text-body">{title}</h3>
            {subtitle && <p className="mt-0.5 text-[12px] text-subtle">{subtitle}</p>}
          </div>
          <button
            onClick={onClose}
            className="shrink-0 rounded-md p-1 text-subtle transition-colors hover:bg-surface hover:text-body"
            aria-label="Close"
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
              <path d="M3.72 3.72a.75.75 0 0 1 1.06 0L8 6.94l3.22-3.22a.749.749 0 1 1 1.06 1.06L9.06 8l3.22 3.22a.749.749 0 1 1-1.06 1.06L8 9.06l-3.22 3.22a.749.749 0 1 1-1.06-1.06L6.94 8 3.72 4.78a.75.75 0 0 1 0-1.06Z" />
            </svg>
          </button>
        </div>

        {/* Item list */}
        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <div className="flex h-32 items-center justify-center text-[13px] text-subtle">Loading…</div>
          ) : items.length === 0 ? (
            <div className="flex h-32 items-center justify-center text-[13px] text-subtle">No items found.</div>
          ) : (
            <ul className="divide-y divide-edge">
              {items.map(item => (
                <li key={item.number} className="px-5 py-3.5 hover:bg-surface transition-colors">
                  {/* Meta row */}
                  <div className="mb-1 flex flex-wrap items-center gap-1.5 text-[11px] text-subtle">
                    <span>{KIND_ICON[item.kind] ?? '○'}</span>
                    <span className="tabular-nums">#{item.number}</span>
                    {item.area && (
                      <span className="rounded-full border border-edge px-1.5 py-0.5">{item.area}</span>
                    )}
                    {item.type && (
                      <span className="rounded-full border border-edge px-1.5 py-0.5">{item.type}</span>
                    )}
                    {item.severity && item.severity !== 'LOW' && (
                      <span className={`rounded-full border px-1.5 py-0.5 ${SEVERITY_COLOR[item.severity] ?? 'border-edge'}`}>
                        {item.severity}
                      </span>
                    )}
                    {item.goodFirstIssue && (
                      <span className="rounded-full border border-success px-1.5 py-0.5 text-success">good first issue</span>
                    )}
                    <span className="ml-auto flex items-center gap-2">
                      <span>👍 {item.reactions}</span>
                      <span>💬 {item.comments}</span>
                    </span>
                  </div>
                  {/* Title — linked */}
                  <a
                    href={item.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="block text-[13px] font-medium leading-snug text-accent hover:underline"
                  >
                    {item.title}
                  </a>
                  {/* Summary */}
                  {item.summary && (
                    <p className="mt-1 text-[12px] leading-relaxed text-subtle line-clamp-2">{item.summary}</p>
                  )}
                </li>
              ))}
            </ul>
          )}

          {/* Semantic nearest neighbors (single-item view only) */}
          {single && (
            <div className="border-t border-edge px-5 py-4">
              <h4 className="mb-2 text-[11px] uppercase tracking-wide text-subtle">
                ≈ Similar items <span className="normal-case">· by meaning · AI-suggested</span>
              </h4>
              {similar === null ? (
                <p className="text-[12px] text-subtle">Finding similar items…</p>
              ) : similar.length === 0 ? (
                <p className="text-[12px] text-subtle">Nothing semantically close in the open backlog.</p>
              ) : (
                <ul className="space-y-2">
                  {similar.map(s => (
                    <li key={s.number} className="flex items-start gap-2 text-[12px]">
                      <span className="shrink-0 rounded bg-primary/10 px-1.5 py-0.5 text-[11px] tabular-nums text-accent">
                        {Math.round(s.similarity * 100)}%
                      </span>
                      <span className="shrink-0 text-subtle">{KIND_ICON[s.kind] ?? '○'} #{s.number}</span>
                      <a
                        href={s.url}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="min-w-0 flex-1 truncate text-accent hover:underline"
                        title={s.title}
                      >
                        {s.title}
                      </a>
                      {s.area && <span className="shrink-0 text-[11px] text-subtle">{s.area}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>

        {!loading && items.length > 0 && (
          <div className="shrink-0 border-t border-edge px-5 py-2.5 text-[11px] text-subtle">
            {items.length} items · sorted by engagement
          </div>
        )}
      </div>

      <style>{`
        @keyframes slideIn {
          from { transform: translateX(100%); opacity: 0; }
          to   { transform: translateX(0);    opacity: 1; }
        }
      `}</style>
    </>
  )
}
