// Paste into the Work browser session after binding the user's filtered tab as tab.
// The browser session exposes nodeRepl and shares /workspace/shared with the workspace.
async function captureFlipkartPages(tab) {
  const fs = await import('node:fs/promises');
  const folder = '/workspace/shared';
  const start = new URL(await tab.url());
  if (start.origin !== 'https://www.flipkart.com' || start.pathname !== '/search' ||
      Number(start.searchParams.get('page') ?? '1') !== 1) {
    throw new Error('Start on page 1 of the user’s filtered Flipkart results');
  }
  const pages = {};
  await fs.mkdir(folder, { recursive: true });
  const runFolder = await fs.mkdtemp(folder + '/flipkart-');
  const capturePath = runFolder + '/flipkart_filtered_results.json';
  try {
    for (let page = 1; ; page++) {
      const results = tab.playwright.getByText(/Showing \d+ [–-] \d+ of [\d,]+ results/).first();
      await results.waitFor({ timeout: 8000 });
      const rows = await tab.playwright.evaluate(() =>
        [...document.querySelectorAll('a[href*="/p/"]')]
          .filter(link => link.innerText.includes('Add to Compare'))
          .map(link => {
            const url = new URL(link.href);
            const pid = url.searchParams.get('pid');
            if (!pid) throw new Error('Listing is missing a product ID');
            return [url.origin + url.pathname + '?pid=' + encodeURIComponent(pid), link.innerText];
          })
      );
      if (!rows.length) throw new Error('No listing cards found on page ' + page);
      pages[page] = rows;
      const next = tab.playwright.getByRole('link', { name: 'Next', exact: true });
      const hasNext = await next.count();
      const path = hasNext ? capturePath + '.partial' : capturePath;
      await fs.writeFile(path + '.tmp', JSON.stringify(pages));
      await fs.rename(path + '.tmp', path);
      if (!hasNext) break;
      const target = new URL(await next.getAttribute('href'), await tab.url());
      const targetPage = Number(target.searchParams.get('page'));
      target.searchParams.delete('page');
      const initialFilters = new URL(start);
      initialFilters.searchParams.delete('page');
      target.searchParams.sort();
      initialFilters.searchParams.sort();
      if (targetPage !== page + 1 || target.href !== initialFilters.href) {
        throw new Error('Next page changed the active search or skipped a page');
      }
      target.searchParams.set('page', String(targetPage));
      await tab.goto(target.href);
    }
  } finally {
    if (await tab.url() !== start.href) await tab.goto(start.href);
  }
  return { capturePath, counts: Object.entries(pages).map(([page, rows]) => [page, rows.length]) };
}
