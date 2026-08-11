import { useEffect, useRef, useState } from 'react'
import { fetchChatStatus, postChat, type ChatResult, type ChatStatus, type ChatTurn, type ItemView } from '../api'

const KIND_ICON: Record<string, string> = { issue: '◎', pr: '⇄' }

const SEVERITY_COLOR: Record<string, string> = {
  CRITICAL: 'text-danger border-danger',
  HIGH:     'text-warn border-warn',
  MEDIUM:   'text-subtle border-subtle',
}

const EXAMPLES = [
  'Is hybrid search a popular theme, and how many unique people are asking?',
  'Is MCP growing, or are we just closing MCP issues slower?',
  'What are the hottest areas right now?',
  'Tell me about #3658 in context of the rest of the backlog',
]

interface AssistantMsg {
  role: 'assistant'
  content: string
  toolsUsed?: string[]
  cards?: ItemView[]
  caveats?: string[]
  error?: boolean
  /** false = no backlog query ran behind this answer */
  grounded?: boolean
}

type Msg = { role: 'user'; content: string } | AssistantMsg

/** The same item-row rendering the drawers use — chat answers ground into real cards. */
function ItemCards({ items }: { items: ItemView[] }) {
  return (
    <ul className="mt-2 divide-y divide-edge rounded-lg border border-edge bg-surface">
      {items.map(item => (
        <li key={item.number} className="px-4 py-3 hover:bg-[#21262d] transition-colors">
          <div className="mb-1 flex flex-wrap items-center gap-1.5 text-[11px] text-subtle">
            <span>{KIND_ICON[item.kind] ?? '○'}</span>
            <span className="tabular-nums">#{item.number}</span>
            {item.area && <span className="rounded-full border border-edge px-1.5 py-0.5">{item.area}</span>}
            {item.type && <span className="rounded-full border border-edge px-1.5 py-0.5">{item.type}</span>}
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
          <a
            href={item.url}
            target="_blank"
            rel="noopener noreferrer"
            className="block text-[13px] font-medium leading-snug text-accent hover:underline"
          >
            {item.title}
          </a>
          {item.summary && (
            <p className="mt-1 text-[12px] leading-relaxed text-subtle line-clamp-2">{item.summary}</p>
          )}
        </li>
      ))}
    </ul>
  )
}

export function AskBacklog() {
  const [messages, setMessages] = useState<Msg[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<ChatStatus | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetchChatStatus().then(setStatus).catch(() => {})
  }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, busy])

  async function send(question: string) {
    const q = question.trim()
    if (!q || busy) return
    // prose-only history; the server truncates to the last 5 exchanges anyway
    const history: ChatTurn[] = messages
      .filter(m => !(m.role === 'assistant' && (m as AssistantMsg).error))
      .slice(-10)
      .map(m => ({ role: m.role, content: m.content }))
    setMessages(ms => [...ms, { role: 'user', content: q }])
    setInput('')
    setBusy(true)
    try {
      const r: ChatResult = await postChat(q, history)
      setMessages(ms => [...ms, {
        role: 'assistant',
        content: r.reply,
        toolsUsed: r.toolsUsed,
        cards: r.cards,
        caveats: r.caveats,
        grounded: r.grounded,
      }])
      fetchChatStatus().then(setStatus).catch(() => {})
    } catch (e) {
      setMessages(ms => [...ms, { role: 'assistant', content: String(e), error: true }])
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto flex h-[calc(100vh-140px)] max-w-3xl flex-col">
      {/* Transcript */}
      <div className="flex-1 space-y-4 overflow-y-auto pb-4">
        {messages.length === 0 && (
          <div className="pt-14 text-center">
            <div className="text-[15px] font-medium text-body">Ask the backlog</div>
            <p className="mx-auto mt-2 max-w-md text-[13px] leading-relaxed text-subtle">
              Questions are routed to the dashboard's own queries — every number comes from
              SQL over GitHub data, and matching items appear as cards. The AI only narrates.
            </p>
            <div className="mx-auto mt-6 flex max-w-xl flex-wrap justify-center gap-2">
              {EXAMPLES.map(ex => (
                <button
                  key={ex}
                  onClick={() => send(ex)}
                  className="rounded-full border border-edge px-3 py-1.5 text-[12px] text-subtle transition-colors hover:border-subtle hover:text-body"
                >
                  {ex}
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map((m, i) =>
          m.role === 'user' ? (
            <div key={i} className="flex justify-end">
              <div className="max-w-[85%] rounded-2xl rounded-br-md bg-primary/20 px-4 py-2.5 text-[13px] leading-relaxed">
                {m.content}
              </div>
            </div>
          ) : (
            <div key={i} className="flex flex-col items-start">
              <div
                className={`max-w-[95%] rounded-2xl rounded-bl-md border px-4 py-2.5 text-[13px] leading-relaxed whitespace-pre-wrap ${
                  m.error ? 'border-danger/50 text-danger' : 'border-edge bg-surface'
                }`}
              >
                {m.content}
              </div>
              {m.toolsUsed && m.toolsUsed.length > 0 && (
                <div className="mt-1.5 flex flex-wrap items-center gap-1 text-[11px] text-subtle">
                  <span className="uppercase tracking-wide">via</span>
                  {[...new Set(m.toolsUsed)].map(t => (
                    <span key={t} className="rounded bg-teal-400/10 px-1.5 py-0.5 text-teal-300">{t}</span>
                  ))}
                </div>
              )}
              {!m.error && m.grounded === false && (
                <div className="mt-1.5 rounded bg-warn/10 px-1.5 py-0.5 text-[11px] text-warn">
                  ⚠ no backlog query ran — this answer is not grounded in the data
                </div>
              )}
              {m.cards && m.cards.length > 0 && <ItemCards items={m.cards} />}
              {m.caveats && m.caveats.length > 0 && (
                <div className="mt-1.5 space-y-0.5">
                  {m.caveats.map((c, j) => (
                    <p key={j} className="text-[11px] italic text-subtle">※ {c}</p>
                  ))}
                </div>
              )}
            </div>
          ),
        )}

        {busy && (
          <div className="flex items-center gap-2 text-[13px] text-subtle">
            <span className="inline-flex h-2 w-2 animate-ping rounded-full bg-teal-400 opacity-75" />
            Querying the backlog…
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Budget-exhausted banner */}
      {status && !status.budgetAvailable && (
        <div className="mb-2 rounded-lg border border-warn/40 bg-warn/10 px-4 py-2 text-[12px] text-warn">
          Today's answer budget is spent — it resets at midnight. The dashboard tabs have the same data.
        </div>
      )}

      {/* Input */}
      <div className="shrink-0">
        <div className="flex items-end gap-2">
          <input
            type="text"
            value={input}
            maxLength={500}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') send(input) }}
            placeholder="Ask about themes, trends, areas, or a specific #number…"
            disabled={busy}
            className="w-full rounded-lg border border-edge bg-surface px-4 py-2.5 text-[14px] placeholder:text-subtle focus:border-primary focus:outline-none disabled:opacity-60"
          />
          <button
            onClick={() => send(input)}
            disabled={busy || !input.trim()}
            className="shrink-0 rounded-lg bg-primary px-4 py-2.5 text-[13px] font-medium text-white transition-opacity disabled:cursor-not-allowed disabled:opacity-40"
          >
            Ask
          </button>
        </div>
        <div className="mt-1.5 flex items-center justify-between text-[11px] text-subtle">
          <span>AI narrates; numbers come from SQL over GitHub data. Read-only toward GitHub.</span>
          {status && (
            <span className="tabular-nums">
              today: ${status.spentTodayUsd.toFixed(2)} / ${status.dailyBudgetUsd.toFixed(2)} · {status.turnsToday} turn{status.turnsToday === 1 ? '' : 's'}
            </span>
          )}
        </div>
      </div>
    </div>
  )
}
