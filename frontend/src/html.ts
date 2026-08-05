/**
 * Escape a string for interpolation into HTML. ECharts tooltips render HTML and do NOT escape
 * values (including the '{b}' template placeholder), so every dynamic string that reaches a
 * tooltip formatter must pass through here — several of them are LLM- or GitHub-derived.
 */
export function escapeHtml(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
