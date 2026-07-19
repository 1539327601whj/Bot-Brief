/**
 * 已保存账户管理（存 localStorage）
 *
 * 仅存**邮箱**和一些展示用元数据（昵称、角色、最近登录时间）——**不存密码**。
 * 密码请让浏览器/系统级密码管理器处理（Chrome/Edge、1Password、Windows Hello 等）。
 * 那些方案用的是 OS 级加密，比任何客户端 JS 方案都安全。
 */

const KEY = 'briefmind_saved_accounts'
const MAX_ACCOUNTS = 6

export interface SavedAccount {
  email: string
  displayName?: string
  role?: 'ADMIN' | 'USER'
  /** ISO 时间戳 */
  lastLoginAt: string
}

function readRaw(): SavedAccount[] {
  try {
    const s = localStorage.getItem(KEY)
    if (!s) return []
    const arr = JSON.parse(s)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

export function listSavedAccounts(): SavedAccount[] {
  return readRaw()
    .filter(a => a && a.email)
    .sort((a, b) => (b.lastLoginAt || '').localeCompare(a.lastLoginAt || ''))
}

export function saveAccount(a: SavedAccount) {
  const list = readRaw().filter(x => x.email !== a.email)
  list.unshift({
    ...a,
    lastLoginAt: a.lastLoginAt || new Date().toISOString(),
  })
  const truncated = list.slice(0, MAX_ACCOUNTS)
  localStorage.setItem(KEY, JSON.stringify(truncated))
}

export function removeSavedAccount(email: string) {
  const list = readRaw().filter(a => a.email !== email)
  localStorage.setItem(KEY, JSON.stringify(list))
}
