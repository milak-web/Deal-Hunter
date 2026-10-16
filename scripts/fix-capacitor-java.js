const fs = require('fs');
const path = require('path');

const patchTargets = [
  path.join(__dirname, '..', 'android', 'app', 'capacitor.build.gradle'),
  path.join(__dirname, '..', 'node_modules', '@capacitor', 'android', 'capacitor', 'build.gradle'),
  path.join(__dirname, '..', 'node_modules', '@capacitor', 'app', 'android', 'build.gradle'),
  path.join(__dirname, '..', 'node_modules', '@capacitor', 'local-notifications', 'android', 'build.gradle')
];

let changedCount = 0;
let checkedCount = 0;

for (const targetFile of patchTargets) {
  if (!fs.existsSync(targetFile)) {
    continue;
  }

  checkedCount += 1;
  const original = fs.readFileSync(targetFile, 'utf8');
  const next = original
    .replace(/sourceCompatibility\s+JavaVersion\.VERSION_\d+/g, 'sourceCompatibility JavaVersion.VERSION_17')
    .replace(/targetCompatibility\s+JavaVersion\.VERSION_\d+/g, 'targetCompatibility JavaVersion.VERSION_17');

  if (next !== original) {
    fs.writeFileSync(targetFile, next, 'utf8');
    changedCount += 1;
    console.log(`[mobile] Patched Java compatibility to VERSION_17 in ${path.relative(path.join(__dirname, '..'), targetFile)}`);
  }
}

if (checkedCount === 0) {
  console.log('[mobile] No Gradle targets found for Java compatibility patch.');
} else if (changedCount === 0) {
  console.log('[mobile] Java compatibility already set to VERSION_17 in all checked targets.');
} else {
  console.log(`[mobile] Java compatibility patch completed for ${changedCount}/${checkedCount} targets.`);
}
