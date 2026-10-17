const fs = require('fs');
const path = require('path');

// Original, short ascending chime; no external sound asset or license is needed.
const rate = 44100;
const length = Math.round(rate * 0.72);
const pcm = Buffer.alloc(length * 2);
const notes = [{ at: 0, hz: 880 }, { at: 0.15, hz: 1174.66 }, { at: 0.30, hz: 1318.51 }];
for (let i = 0; i < length; i++) {
    const time = i / rate;
    let sample = 0;
    for (const note of notes) {
        const elapsed = time - note.at;
        if (elapsed < 0 || elapsed > 0.40) continue;
        const envelope = Math.min(1, elapsed / 0.012) * Math.exp(-elapsed * 12) * Math.min(1, (0.40 - elapsed) / 0.035);
        sample += envelope * (Math.sin(2 * Math.PI * note.hz * elapsed) + 0.15 * Math.sin(4 * Math.PI * note.hz * elapsed));
    }
    pcm.writeInt16LE(Math.round(Math.max(-1, Math.min(1, sample * 0.20)) * 32767), i * 2);
}
const header = Buffer.alloc(44);
header.write('RIFF', 0); header.writeUInt32LE(36 + pcm.length, 4); header.write('WAVEfmt ', 8);
header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22);
header.writeUInt32LE(rate, 24); header.writeUInt32LE(rate * 2, 28);
header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34);
header.write('data', 36); header.writeUInt32LE(pcm.length, 40);
const target = path.resolve(__dirname, '../android/app/src/main/res/raw/dealhunter_chime.wav');
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.writeFileSync(target, Buffer.concat([header, pcm]));
console.log('Generated Deal Hunter notification chime');
