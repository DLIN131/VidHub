// 網站網址（可在設定頁的「網址設定」改）和程式設定
const { app } = require('electron')
const fs = require('fs')
const path = require('path')

// 預設網址只用 Base64 存
const DEFAULT_BASE = Buffer.from('aHR0cHM6Ly9yb3V2YjEueHl6', 'base64').toString()

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

/** 使用者輸入的網址整理成 https://host；格式不對丟出錯誤 */
function normalizeBase(input) {
  let s = String(input || '').trim()
  if (!s) return ''
  if (!/^https?:\/\//i.test(s)) s = 'https://' + s
  const u = new URL(s)
  if (!u.hostname.includes('.')) throw new Error('網址格式不正確')
  return `${u.protocol}//${u.host}`
}

const base = () => get('base') || DEFAULT_BASE

module.exports = { DEFAULT_BASE, base, normalizeBase, get, set }
