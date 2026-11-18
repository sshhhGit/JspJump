/*
 * Builds the bookmarklet URLs from jspjump.js.
 *   node bookmarklet/build.js            -> writes bookmarklet/dist/*.txt
 *   node bookmarklet/build.js 63343      -> pins the full version to one IDE port (no auto-detection)
 */
'use strict';
const fs = require('fs');
const path = require('path');

const port = process.argv[2] || '63342';
if (!/^\d{2,5}$/.test(port)) throw new Error('port must be a number: ' + port);
const src = fs.readFileSync(path.join(__dirname, 'jspjump.js'), 'utf8');

let code = src
  .replace(/\/\*[\s\S]*?\*\//g, '')          // block comments
  .split(/\r?\n/).map((l) => l.trim()).filter(Boolean).join(' ');
if (port !== '63342') code = `window.JSPJUMP_ENDPOINT='http://localhost:${port}/jspjump';` + code;

new Function(code); // syntax check of our own source only (not executed)

const full = 'javascript:' + encodeURIComponent(code).replace(/'/g, '%27');
// loader.js: tries the port remembered for the site, then 63342..63361 (same text as the IDE install page)
const loader = 'javascript:' + encodeURIComponent(
  fs.readFileSync(path.join(__dirname, 'loader.js'), 'utf8').trim()).replace(/'/g, '%27');

const out = path.join(__dirname, 'dist');
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, 'bookmarklet-full.txt'), full + '\n');
fs.writeFileSync(path.join(out, 'bookmarklet-loader.txt'), loader + '\n');
console.log(`full   : ${full.length} chars -> bookmarklet/dist/bookmarklet-full.txt`);
console.log(`loader : ${loader.length} chars -> bookmarklet/dist/bookmarklet-loader.txt`);
