// One cross-site hop between two real-looking hostnames, under several Chromium flag sets: isolates
// which switch makes Chromium clear window.name, independent of localhost/IP-address quirks.
//
//   PW_CORE=<path to a playwright-core install> node chromium-flags.mjs     (server.py running)
import { createRequire } from 'node:module';
const pw = createRequire(import.meta.url)(process.env.PW_CORE);
const on = ['--disable-back-forward-cache', '--disable-field-trial-config'];
const variants = {
  'Playwright default args': { args: [] },
  'bfcache + field trials on': { ignoreDefaultArgs: on, args: [] },
  'bfcache on, +ClearCrossSiteCrossBrowsingContextGroupWindowName': {
    ignoreDefaultArgs: on, args: ['--enable-features=ClearCrossSiteCrossBrowsingContextGroupWindowName'] },
  'bfcache off, +ClearCrossSiteCrossBrowsingContextGroupWindowName': {
    args: ['--enable-features=ClearCrossSiteCrossBrowsingContextGroupWindowName'] },
};
for (const [name, v] of Object.entries(variants)) {
  const b = await pw.chromium.launch({ channel: 'chromium', headless: !process.env.HEADED, ...v,
    args: [...v.args, '--host-resolver-rules=MAP app.example 127.0.0.1, MAP pay.test 127.0.0.1'] });
  const p = await b.newPage();
  await p.goto('http://app.example:8765/app');
  const orig = await p.evaluate(() => window.name);
  await p.evaluate(() => { location.href = 'http://pay.test:8766/pay'; });
  await p.waitForURL(/pay\.test/); await p.waitForLoadState('load');
  const onPay = await p.evaluate(() => window.name);
  await p.evaluate(() => { location.href = 'http://app.example:8765/app?back'; });
  await p.waitForURL(/app\.example/); await p.waitForLoadState('load');
  const back = await p.evaluate(() => window.__probe);
  console.log(JSON.stringify({ variant: name, version: b.version(), orig, onPay,
    backName: back.windowName, verdict: back.verdict, keptId: back.id === orig }));
  await b.close();
}
