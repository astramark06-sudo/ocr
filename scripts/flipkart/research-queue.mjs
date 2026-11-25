// Build a private, read-only research handoff from an existing checkpoint.
// Usage: node research-queue.mjs checkpoint.json output.json [budget]
import {readFileSync, writeFileSync} from "node:fs";
import {pathToFileURL} from "node:url";

export function buildResearchQueue(checkpoint, budget = 55000) {
  if (!Number.isFinite(budget) || budget < 0) throw new Error("Invalid budget");
  const refs = checkpoint.manufacturer_reference_research_queue;
  const reviews = checkpoint.reviewed_browser_queue;
  if (!Array.isArray(refs) || !Array.isArray(reviews)) throw new Error("Checkpoint missing research queues");
  const groups = checkpoint.pid_groups || {};
  const records = new Map((checkpoint.match_records || []).map(r => [r.listing_id, r]));
  const seen = new Set();
  const manufacturer = [], identify = [], outside = [], unknown = [];
  const browser = new Map();
  function safeLinks(pid) {
    const urls = groups[pid]?.L || [];
    return [...new Set(urls.flatMap(raw => {
      if (typeof raw !== "string") return [];
      const m = raw.match(/^(https:\/\/(?:www\.)?flipkart\.com\/[a-z0-9_/%.-]*\/p\/itm[a-z0-9]+)\?([^#]*)(?:#.*)?$/i);
      if (!m) return [];
      const pids = m[2].split("&").filter(x=>x.startsWith("pid="));
      return pids.length === 1 && pids[0] === "pid="+pid ? [m[1]+"?pid="+pid] : [];
    }))].sort();
  }
  function addBrowser(id, pid, reason, priority) {
    if (typeof pid !== "string" || !/^[A-Z0-9]{8,30}$/.test(pid)) return;
    let entry = browser.get(pid);
    if (!entry) {
      entry = {pid, listing_ids: [], reasons: [], priority, urls: safeLinks(pid)};
      browser.set(pid, entry);
    }
    if (!entry.listing_ids.includes(id)) entry.listing_ids.push(id);
    if (!entry.reasons.includes(reason)) entry.reasons.push(reason);
    entry.priority = Math.min(entry.priority, priority);
  }
  for (const r of refs) {
    if (!r.listing_id || seen.has(r.listing_id)) throw new Error("Missing or duplicate research listing ID");
    seen.add(r.listing_id);
    const item = {listing_id:r.listing_id, screenshot_price:r.screenshot_price ?? null, captured_model:r.captured_model ?? null, part:r.part ?? null, cpu:r.cpu ?? null, title:r.title ?? null, pid:r.pid ?? null, match_status:r.match_status ?? null};
    if (typeof item.screenshot_price !== "number" || !Number.isFinite(item.screenshot_price) || item.screenshot_price <= 0) {unknown.push(item); continue;}
    if (item.screenshot_price > budget) {outside.push(item); continue;}
    if (item.captured_model || item.part) manufacturer.push(item);
    else {
      identify.push(item);
      const candidates = records.get(r.listing_id)?.candidates || [];
      if (item.pid) addBrowser(item.listing_id,item.pid,"Missing model/part identifier",2);
      for (const c of candidates) if (c.valid_url_pid && !c.conflicts?.length) addBrowser(item.listing_id,c.pid,"Candidate only; verify selected configuration",3);
    }
  }
  for (const r of reviews) addBrowser(r.listing_id,r.pid,r.reason || "Reviewed priority; verify selected configuration",1);
  const byPrice = (a,b) => a.screenshot_price-b.screenshot_price || a.listing_id.localeCompare(b.listing_id);
  manufacturer.sort(byPrice); identify.sort(byPrice); outside.sort(byPrice);
  const browserQueue = [...browser.values()].sort((a,b)=>a.priority-b.priority || a.pid.localeCompare(b.pid));
  for (const r of browserQueue) r.listing_ids.sort();
  return {checkpoint:checkpoint.checkpoint ?? null, workbook_sha256:checkpoint.workbook_sha256 ?? null, budget, counts:{reference_gaps:refs.length, within_budget:manufacturer.length+identify.length, candidate_identifiers:manufacturer.length, needs_identification:identify.length, outside_budget:outside.length, unknown_price:unknown.length, browser_distinct_pids:browserQueue.length, reviewed_priority_rows:reviews.length}, manufacturer, identify, outside, unknown, browser:browserQueue, policy:"Private research handoff only. Candidate identifiers and PIDs are not confirmed SKU assignments. Original screenshot prices must not be replaced. Browser reasons are review leads, not approval to join records."};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [input, output, limit] = process.argv.slice(2);
  if (!input || !output) throw new Error("Usage: node research-queue.mjs checkpoint.json output.json [budget]");
  const queue = buildResearchQueue(JSON.parse(readFileSync(input, "utf8")), limit === undefined ? 55000 : Number(limit));
  writeFileSync(output, JSON.stringify(queue, null, 2) + "\n", {flag:"wx", mode:0o600});
  console.log(JSON.stringify(queue.counts));
}
