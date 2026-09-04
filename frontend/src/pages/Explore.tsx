import { useState } from 'react'
import { AskBacklog } from './AskBacklog'
import { Search } from './Search'

// Search and Ask share one tab: both are ways of finding things in the backlog, and neither
// earns a top-level slot on its own. The mode switch keeps each page's own state while mounted.

type Mode = 'search' | 'ask'

export function Explore() {
  const [mode, setMode] = useState<Mode>('search')
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-1.5 text-[12px]">
        <button
          onClick={() => setMode('search')}
          className={`rounded-md px-3 py-1 transition-colors ${mode === 'search' ? 'bg-indigo-400/10 text-indigo-300' : 'text-subtle hover:bg-[#21262d]'}`}
        >
          ⌕ Search
        </button>
        <button
          onClick={() => setMode('ask')}
          className={`rounded-md px-3 py-1 transition-colors ${mode === 'ask' ? 'bg-teal-400/10 text-teal-300' : 'text-subtle hover:bg-[#21262d]'}`}
        >
          ✦ Ask
        </button>
      </div>
      {mode === 'search' ? <Search /> : <AskBacklog />}
    </div>
  )
}
