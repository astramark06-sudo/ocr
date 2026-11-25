import fs from 'node:fs/promises';
import {Workbook, SpreadsheetFile} from '@oai/artifact-tool';
const [input, destination, capturedAt, filters] = process.argv.slice(2);
if (!input || !destination || !capturedAt || !filters) {
  throw new Error('Usage: build-live-sheet.mjs CAPTURE.json LIVE.xlsx CAPTURE_DATE FILTER_DESCRIPTION');
}
const pages = JSON.parse(await fs.readFile(input, 'utf8'));
const pageNumbers = Object.keys(pages);
if (!pageNumbers.length || pageNumbers.some((page, index) =>
  Number(page) !== index + 1 || !Array.isArray(pages[page]) || !pages[page].length
)) {
  throw new Error('Capture must contain consecutively numbered non-empty pages');
}
const workbook = Workbook.create();
const sheet = workbook.worksheets.add('Flipkart Live Results');
const headers = ['Page', 'Position', 'Flipkart product ID', 'Listing title', 'Live selling price (₹)', 'Processor', 'Memory', 'Storage', 'Operating system', 'Display', 'Listing details', 'Product URL'];
const rows = Object.entries(pages).flatMap(([page, entries]) => entries.map(([href, raw], index) => {
  const url = new URL(href);
  const pid = url.searchParams.get('pid');
  if (url.origin !== 'https://www.flipkart.com' || !url.pathname.includes('/p/') || !pid) {
    throw new Error(`Invalid Flipkart product identity on page ${page}, row ${index + 1}`);
  }
  const lines = raw.split('\n').map(line=>line.trim()).filter(Boolean);
  const priceIndex = lines.findIndex(line=>/^₹[\d,]+$/.test(line));
  const priceBoundary = lines.findIndex(line => /^₹/.test(line));
  const details = lines.slice(2, priceBoundary >= 0 ? priceBoundary : undefined);
  const find = pattern=>details.find(line=>pattern.test(line)) ?? null;
  return [Number(page), index+1, pid, lines[1], priceIndex>=0 ? Number(lines[priceIndex].replace(/[^\d]/g,'')) : null,
    find(/Processor/i), find(/\bRAM\b/i), find(/\b(SSD|HDD|eMMC|UFS)\b/i), find(/Operating System/i), find(/Display/i), details.join(' | '), url.origin+url.pathname+'?pid='+encodeURIComponent(pid)];
}));
const last = rows.length+4;
sheet.getRange('A1').values=[['Flipkart live laptop results']];
sheet.getRange('A2').values=[[`${capturedAt} • ${filters} • Repeated product IDs retained in page order`]];
sheet.getRange('A3').values=[['Live search cards. Empty prices were unavailable or could not be parsed. Product titles may be shortened by Flipkart.']];
sheet.getRange(`A4:L${last}`).values=[headers,...rows];
for (const [index,width] of [8,10,22,70,23,36,22,20,36,32,70,58].entries()) {
  const col=String.fromCharCode(65+index);
  sheet.getRange(`${col}:${col}`).format.columnWidth=width;
}
const output=await SpreadsheetFile.exportXlsx(workbook);
await output.save(destination);
console.log(JSON.stringify({observations:rows.length,products:new Set(rows.map(r=>r[2])).size,priced:rows.filter(r=>r[4]!==null).length}));
