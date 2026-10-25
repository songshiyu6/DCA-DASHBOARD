// Browser preferences are optional; storage policies and quotas must not break the workspace.
export function readPreference(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export function writePreference(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // The current React state remains usable even when it cannot be persisted.
  }
}
