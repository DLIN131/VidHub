// 網站網址（可在「網址設定」改）與廣告攔截規則
const { app } = require('electron')
// 要用一般的 fs：規則檔打包在 app.asar 裡，original-fs 讀不到
const fs = require('fs')
const path = require('path')

// 預設網址只用 Base64 存
const DEFAULT_BASE = Buffer.from('aHR0cHM6Ly9yb3V2YjEueHl6', 'base64').toString()

/** 共用檔案（規則、注入腳本）：打包時 pack.js 會複製到 desktop/shared，開發時讀上一層的 shared */
function sharedFile(name) {
  const local = path.join(__dirname, 'shared', name)
  return fs.existsSync(local) ? local : path.join(__dirname, '..', 'shared', name)
}

const rules = JSON.parse(fs.readFileSync(sharedFile('rules.json'), 'utf8'))
const injectJs = fs.readFileSync(sharedFile('inject.js'), 'utf8')

const settingsFile = () => path.join(app.getPath('userData'), 'settings.json')
let settings = null

function load() {
  if (settings) return settings
  try {
    settings = JSON.parse(fs.readFileSync(settingsFile(), 'utf8'))
  } catch {
    settings = {}
  }
  return settings
}

function save() {
  try {
    fs.mkdirSync(path.dirname(settingsFile()), { recursive: true })
    fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2))
  } catch {}
}

function get(key) {
  return load()[key]
}

function set(key, value) {
  load()
  if (value === undefined || value === null || value === '') delete settings[key]
  else settings[key] = value
  save()
}

/** 使用者輸入的網址整理成 https://host */
function normalizeBase(input) {
  let s = String(input || '').trim()
  if (!s) return ''
  if (!/^https?:\/\//i.test(s)) s = 'https://' + s
  const u = new URL(s)
  return `${u.protocol}//${u.host}`
}

const base = () => get('base') || DEFAULT_BASE
const host = () => new URL(base()).hostname

const isSite = (h) => h === host() || h.endsWith('.' + host())

/** 站外的請求只放行規則裡允許的（影片片段、縮圖、字型…），其餘都是廣告和追蹤 */
function shouldBlock(url) {
  let u
  try {
    u = new URL(url)
  } catch {
    return false
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return false
  const h = u.hostname
  if (isSite(h)) return rules.blockSitePaths.some((p) => u.pathname.startsWith(p))
  if (rules.allowHosts.includes(h)) return false
  if (rules.allowHostSuffixes.some((s) => h === s || h.endsWith('.' + s))) return false
  return !rules.allowPathPrefixes.some((p) => u.pathname.startsWith(p))
}

module.exports = { DEFAULT_BASE, base, host, isSite, shouldBlock, normalizeBase, get, set, injectJs }
