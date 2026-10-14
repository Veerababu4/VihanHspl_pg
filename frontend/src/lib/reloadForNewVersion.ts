const KEY = 'reloadedForNewVersion'

/**
 * Rejection handler for lazy page imports. After a deploy, a tab opened earlier still asks for the
 * old page files, which are gone ("Failed to fetch dynamically imported module"): reload once to
 * pick up the new version, and keep the loading state until then. The time check stops a reload
 * loop when a file is really missing; the error then reaches the error page.
 */
export function reloadForNewVersion(error: unknown): Promise<never> {
  const last = Number(sessionStorage.getItem(KEY) ?? 0)
  if (Date.now() - last < 10_000) return Promise.reject(error)
  sessionStorage.setItem(KEY, String(Date.now()))
  window.location.reload()
  return new Promise<never>(() => {})
}
