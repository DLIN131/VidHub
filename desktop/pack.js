// 打包 Windows 版：node pack.js [輸出資料夾]
const { packager } = require('@electron/packager')
const path = require('path')
const fs = require('fs')

const out = process.argv[2] || 'dist'
const version = process.env.APP_VERSION || require('./package.json').version

packager({
  dir: __dirname,
  name: 'VidHub',
  platform: 'win32',
  arch: 'x64',
  out,
  overwrite: true,
  asar: true,
  icon: path.join(__dirname, 'icon.ico'),
  appVersion: version,
  // 程式裡的 app.getVersion() 讀的是打包進去的 package.json，不是 exe 的版本資訊；
  // 沒改的話裝完新版還是顯示舊版本號，更新視窗會一直跳出來
  afterCopy: [
    (buildPath, _electronVersion, _platform, _arch, done) => {
      const file = path.join(buildPath, 'package.json')
      const pkg = JSON.parse(fs.readFileSync(file, 'utf8'))
      pkg.version = version
      fs.writeFileSync(file, JSON.stringify(pkg, null, 2))
      done()
    },
  ],
  win32metadata: { CompanyName: 'VidHub', FileDescription: 'VidHub', ProductName: 'VidHub' },
  // 只排除專案根目錄的東西（路徑以 / 開頭、相對於專案資料夾）
  ignore: [/^\/dist($|\/)/, /^\/out($|\/)/, /^\/tools($|\/)/, /^\/pack\.js$/, /^\/\.gitignore$/],
})
  .then(([appPath]) => {
    // Chromium 介面語言檔只留中文和英文（其他語言約 40 MB，程式用不到）
    const keep = new Set(['en-US.pak', 'zh-TW.pak', 'zh-CN.pak'])
    const locales = path.join(appPath, 'locales')
    for (const f of fs.readdirSync(locales)) if (!keep.has(f)) fs.rmSync(path.join(locales, f))
    fs.writeFileSync(path.join(appPath, 'version.txt'), version)
    console.log(`打包完成：${appPath}（v${version}）`)
  })
  .catch((err) => {
    console.error(err)
    process.exit(1)
  })
