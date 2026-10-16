const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..');
const outDir = path.join(projectRoot, 'mobile_web');
const sourceDataDir = path.join(projectRoot, 'data');
const outDataDir = path.join(outDir, 'data');

function ensureDir(dirPath) {
  if (!fs.existsSync(dirPath)) fs.mkdirSync(dirPath, { recursive: true });
}

function copyFile(from, to) {
  ensureDir(path.dirname(to));
  fs.copyFileSync(from, to);
}

function copyIfExists(from, to) {
  if (fs.existsSync(from)) copyFile(from, to);
}

function writeMobileConfig() {
  const sourceConfig = path.join(projectRoot, 'mobile-config.js');
  const targetConfig = path.join(outDir, 'mobile-config.js');
  if (fs.existsSync(sourceConfig)) {
    copyFile(sourceConfig, targetConfig);
    return;
  }

  const template = `// Optional: set your production backend URL for APK builds.
// Example:
// window.__FDS_API_BASE__ = "https://your-api-domain.com";
window.__FDS_API_BASE__ = "";
`;
  fs.writeFileSync(targetConfig, template, 'utf8');
}

function main() {
  ensureDir(outDir);
  ensureDir(outDataDir);

  copyFile(path.join(projectRoot, 'index.html'), path.join(outDir, 'index.html'));
  copyFile(path.join(projectRoot, 'lib', 'deal-feed.js'), path.join(outDir, 'lib', 'deal-feed.js'));
  fs.cpSync(path.join(projectRoot, 'vendor'), path.join(outDir, 'vendor'), { recursive: true });
  fs.cpSync(path.join(projectRoot, 'assets'), path.join(outDir, 'assets'), { recursive: true });
  copyIfExists(path.join(projectRoot, 'manifest.json'), path.join(outDir, 'manifest.json'));
  copyIfExists(process.env.DEALHUNTER_BUNDLE_FEED || path.join(sourceDataDir, 'deals.json'), path.join(outDataDir, 'deals.json'));
  copyIfExists(path.join(sourceDataDir, 'ads.json'), path.join(outDataDir, 'ads.json'));
  writeMobileConfig();

  console.log(`[mobile] Prepared web bundle at ${outDir}`);
}

main();
