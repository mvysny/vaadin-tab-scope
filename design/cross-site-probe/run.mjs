// Drives the two-site harness (server.py, running) through each scenario; one JSON line per
// scenario says what the candidate bootstrap heuristic saw and decided:
//
//   PW_CORE=~/.npm/_npx/<hash>/node_modules/playwright-core node run.mjs firefox
//   {"scenario":"pay→link back","verdict":"returning","keptId":true,"nameAtLoad":"\"\"",…}
//
// `nameAtLoad` is what Flow would read today; `verdict`/`keptId` is what the heuristic makes of
// it. Chromium only:
//   BFCACHE=1    drops Playwright's --disable-back-forward-cache and --disable-field-trial-config;
//                without it Chromium never swaps browsing-context group, so never clears the name
//   FEATURES=…   extra --enable-features, e.g. ClearCrossSiteCrossBrowsingContextGroupWindowName
// Any browser: ONLY=… runs the scenarios whose name contains it.
import { createRequire } from 'node:module';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const pw = require(process.env.PW_CORE);
const here = path.dirname(fileURLToPath(import.meta.url));
const APP = 'http://localhost:8765/app';
const which = process.argv[2] || 'chromium';

const probe = (page) => page.evaluate(() => ({ ...window.__probe, events: window.__events }));

async function fresh(ctx) {
  const page = await ctx.newPage();
  await page.goto(APP);
  return { page, orig: await probe(page) };
}

// Leaves the app for the "payment provider" by a link, then comes back through `act`. The wait is
// on commit plus the probe global, because a bfcache restore never fires `load`.
async function viaPay(ctx, act) {
  const { page, orig } = await fresh(ctx);
  await Promise.all([page.waitForURL(/8766\/pay/), page.click('#topay')]);
  const pay = await page.evaluate(() => window.__pay);
  await Promise.all([page.waitForURL(/8765\/app/, { waitUntil: 'commit' }), act(page)]);
  await page.waitForFunction(() => window.__probe);
  return { orig, after: await probe(page), payName: pay.windowName };
}

// Opens a second tab from the app, probes it, then reloads the original to show it kept its id.
async function popup(ctx, open) {
  const { page, orig } = await fresh(ctx);
  const [p] = await Promise.all([ctx.waitForEvent('page'), open(page)]);
  await p.waitForLoadState('load');
  const after = await probe(p);
  await page.reload();
  return { orig, after, origAfterReload: await probe(page) };
}

const scenarios = {
  reload: async (ctx) => {
    const { page, orig } = await fresh(ctx);
    await page.reload();
    return { orig, after: await probe(page) };
  },
  inapp: async (ctx) => {
    const { page, orig } = await fresh(ctx);
    await Promise.all([page.waitForURL(/p=2/), page.click('#inapp')]);
    return { orig, after: await probe(page) };
  },
  'pay→link back': (ctx) => viaPay(ctx, (p) => p.click('#click')),
  'pay→302 back': (ctx) => viaPay(ctx, (p) => p.click('#bounce')),
  'pay→js back': (ctx) => viaPay(ctx, (p) => p.evaluate(() => go('js'))),
  'pay→POST back': (ctx) => viaPay(ctx, (p) => p.evaluate(() => go('form'))),
  'pay→Back button': (ctx) => viaPay(ctx, (p) => p.goBack({ waitUntil: 'commit' })),
  'pure 302 via pay': async (ctx) => {
    const { page, orig } = await fresh(ctx);
    await Promise.all([page.waitForURL(/from=bounce/), page.click('#bounce')]);
    await page.waitForLoadState('load');
    return { orig, after: await probe(page) };
  },
  'window.open': (ctx) => popup(ctx, (p) => p.evaluate(() => { window.open('/app?popup'); })),
  'window.open noopener': (ctx) => popup(ctx, (p) => p.evaluate(() => { window.open('/app?popup', '_blank', 'noopener'); })),
  'target=_blank link': (ctx) => popup(ctx, (p) => p.click('#blank')),
};

// Duplicate Tab goes through the extension in ./ext, since CDP has no duplicate-target command.
const chromiumOnly = {
  'Duplicate Tab': async (ctx, sw) => {
    const { page, orig } = await fresh(ctx);
    await page.evaluate(() => history.replaceState(null, '', '/app?dup=' + Date.now()));
    const [p] = await Promise.all([ctx.waitForEvent('page'), sw.evaluate((u) => self.duplicateTab(u), page.url())]);
    await p.waitForLoadState('load');
    const after = await probe(p);
    await page.reload();
    return { orig, after, origAfterReload: await probe(page) };
  },
  // The heuristic's known weak spot: the copy is taken while the tab is away, marker and all.
  'Duplicate while away at pay': async (ctx, sw) => {
    const { page, orig } = await fresh(ctx);
    const payUrl = 'http://127.0.0.1:8766/pay?away' + Date.now();
    await Promise.all([page.waitForURL(/8766\/pay/), page.goto(payUrl)]);
    const [p] = await Promise.all([ctx.waitForEvent('page'), sw.evaluate((u) => self.duplicateTab(u), payUrl)]);
    await p.waitForLoadState('load');
    await Promise.all([page.waitForURL(/8765\/app/), page.click('#click')]);
    await Promise.all([p.waitForURL(/8765\/app/), p.click('#click')]);
    return { orig, after: await probe(p), origAfterReload: await probe(page) };
  },
};

let ctx, sw;
if (which === 'chromium') {
  const ext = path.join(here, 'ext');
  ctx = await pw.chromium.launchPersistentContext(mkdtempSync(path.join(tmpdir(), 'probe-')), {
    channel: 'chromium', headless: true,
    ignoreDefaultArgs: process.env.BFCACHE ? ['--disable-back-forward-cache', '--disable-field-trial-config'] : [],
    args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`,
      ...(process.env.FEATURES ? [`--enable-features=${process.env.FEATURES}`] : [])],
  });
  sw = ctx.serviceWorkers()[0] || (await ctx.waitForEvent('serviceworker'));
  Object.assign(scenarios, chromiumOnly);
} else {
  const browser = await pw.firefox.launch({ headless: true });
  ctx = await browser.newContext();
}
console.log(which, ctx.browser()?.version() ?? '(persistent context)');

for (const [name, fn] of Object.entries(scenarios).filter(([n]) => !process.env.ONLY || n.includes(process.env.ONLY))) {
  try {
    const r = await fn(ctx, sw);
    const a = r.after;
    const bfcache = (a.events || []).some((e) => e.includes('persisted=true'));
    console.log(JSON.stringify({
      scenario: name,
      verdict: bfcache ? '(bfcache, no re-run)' : a.verdict,
      keptId: a.id === r.orig.id,
      nameAtLoad: a.windowName === r.orig.id ? '=orig' : JSON.stringify(a.windowName),
      stored: a.storedId === null ? 'none' : a.storedId === r.orig.id ? '=orig' : a.storedId,
      marker: !!a.leftMarker,
      navType: a.navType,
      referrer: a.referrer,
      payName: r.payName === undefined ? '' : JSON.stringify(r.payName),
      origStill: r.origAfterReload ? `${r.origAfterReload.verdict}/${r.origAfterReload.id === r.orig.id ? 'kept' : 'LOST'}` : '',
    }));
  } catch (e) {
    console.log(JSON.stringify({ scenario: name, error: String(e).split('\n')[0] }));
  }
}
await ctx.close();
process.exit(0);
