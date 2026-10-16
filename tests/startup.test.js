const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const feed = require('../lib/deal-feed');

const root = path.resolve(__dirname, '..');
const fixture = {
    lastUpdated: new Date().toISOString(),
    deals: [{ title: 'Startup regression fixture', platform: 'Steam', category: 'Game',
        link: 'https://store.steampowered.com/app/123/', originalPrice: '$10',
        expiryDate: new Date(Date.now() + 86400000).toISOString() }]
};
const settle = () => new Promise(resolve => setTimeout(resolve, 60));

function createApp({ cached, reply = fixture, url = 'https://localhost', native = true, mobile = true, savedLayout } = {}) {
    const dom = new JSDOM(fs.readFileSync(path.join(root, 'index.html'), 'utf8'), {
        url, runScripts: 'outside-only', pretendToBeVisual: true
    });
    const w = dom.window;
    const requests = [];
    const errors = [];
    w.console.error = (...args) => errors.push(args);
    w.console.warn = () => {};
    w.matchMedia = () => ({ matches: mobile });
    w.HTMLElement.prototype.scrollIntoView = () => {};
    w.scrollTo = () => {};
    w.Capacitor = { isNativePlatform: () => native, Plugins: {
        DealHunterWidget: {
            consumeLaunchTarget: async () => ({ hasTarget: false }),
            getCachedDeals: async () => ({ payload: null }),
            syncWidgetSnapshot: async () => {}
        },
        LocalNotifications: { checkPermissions: async () => ({ display: 'granted' }) },
        App: { addListener: () => {} }
    } };
    if (cached) w.localStorage.setItem('dealhunter_cached_deals_payload_v1', JSON.stringify(cached));
    if (savedLayout) w.localStorage.setItem('dealhunter_layout_mode_v1', savedLayout);
    w.fetch = async url => {
        requests.push(String(url));
        if (String(url).includes('ads')) return { ok: true, json: async () => ({ enabled: false }) };
        if (reply instanceof Error) throw reply;
        return { ok: true, json: async () => structuredClone(reply) };
    };
    for (const file of ['vendor/dayjs.min.js', 'vendor/relativeTime.js', 'lib/deal-feed.js', 'mobile-config.js']) {
        w.eval(fs.readFileSync(path.join(root, file), 'utf8'));
    }
    const script = [...w.document.scripts].find(script => script.textContent.includes('let allDeals'));
    w.eval(script.textContent);
    return { dom, w, requests, errors };
}

test('Capacitor Android https://localhost is a static-feed client', () => {
    assert.equal(feed.isNativeApp({ protocol: 'https:', hostname: 'localhost' }, { isNativePlatform: () => true }), true);
    assert.equal(feed.isNativeApp({ protocol: 'http:', hostname: 'localhost' }, null), false);
});

test('cold Android launch loads actual cards automatically from the remote feed', async () => {
    const app = createApp();
    try {
        await settle();
        assert.ok(app.requests.some(url => url.startsWith('https://milak-web.github.io/Deal-Hunter/data/deals.json')));
        assert.ok(!app.requests.some(url => url.startsWith('/api/deals')));
        assert.match(app.w.document.getElementById('compactLeadList').textContent, /Startup regression fixture/i);
        assert.equal(app.w.document.getElementById('compactLeadSection').classList.contains('hidden'), false);
        assert.equal(app.w.document.getElementById('noDeals').classList.contains('hidden'), true);
        assert.equal(app.errors.length, 0);
    } finally { app.dom.window.close(); }
});

test('GitHub Pages opens the app-style compact feed on a desktop browser', async () => {
    const app = createApp({ url: 'https://milak-web.github.io/Deal-Hunter/', native: false, mobile: false });
    try {
        await settle();
        assert.equal(app.w.document.getElementById('compactLeadSection').classList.contains('hidden'), false);
        assert.ok(!app.requests.some(url => url.startsWith('/api/')));
        assert.match(app.w.document.getElementById('stats').textContent, /You Saved/);
        assert.equal(app.errors.length, 0);
    } finally { app.dom.window.close(); }
});

test('the website preserves an explicitly saved Grid preference', async () => {
    const app = createApp({ url: 'https://milak-web.github.io/Deal-Hunter/', native: false, savedLayout: 'grid' });
    try {
        await settle();
        assert.equal(app.w.document.getElementById('compactLeadSection').classList.contains('hidden'), true);
        assert.equal(app.w.document.getElementById('dealsGrid').classList.contains('hidden'), false);
        assert.ok(app.w.document.getElementById('dealsGrid').children.length > 0);
    } finally { app.dom.window.close(); }
});

test('a Compact link overrides a saved Grid preference without erasing it', async () => {
    const app = createApp({ url: 'https://milak-web.github.io/Deal-Hunter/?layout=compact', native: false, savedLayout: 'grid' });
    try {
        await settle();
        assert.equal(app.w.document.getElementById('compactLeadSection').classList.contains('hidden'), false);
        assert.equal(app.w.localStorage.getItem('dealhunter_layout_mode_v1'), 'grid');
    } finally { app.dom.window.close(); }
});

test('cached cards render immediately and survive a failed manual refresh', async () => {
    const app = createApp({ cached: fixture, reply: new Error('offline') });
    try {
        assert.match(app.w.document.getElementById('compactLeadList').textContent, /Startup regression fixture/i);
        await settle();
        app.w.document.getElementById('refreshBtn').click();
        await settle();
        assert.equal(app.w.document.getElementById('compactLeadSection').classList.contains('hidden'), false);
        assert.equal(app.w.document.getElementById('noDeals').classList.contains('hidden'), true);
        assert.match(app.w.document.getElementById('compactLeadList').textContent, /Startup regression fixture/i);
    } finally { app.dom.window.close(); }
});

test('malformed API responses cannot erase a cached feed', async () => {
    const app = createApp({ cached: fixture, reply: { error: 'unavailable' } });
    try {
        await settle();
        assert.equal(app.w.document.getElementById('compactLeadSection').classList.contains('hidden'), false);
        assert.match(app.w.document.getElementById('compactLeadList').textContent, /Startup regression fixture/i);
    } finally { app.dom.window.close(); }
});

test('an older empty bundle cannot overwrite a current snapshot, including manual refresh', () => {
    assert.equal(feed.shouldAccept(fixture, { lastUpdated: '2020-01-01T00:00:00Z', deals: [] }, 'local-bundle'), false);
    assert.equal(feed.shouldAccept(fixture, { lastUpdated: '2020-01-01T00:00:00Z', deals: [] }, 'github-feed'), false);
});

test('a newer authoritative empty feed removes expired or withdrawn listings', () => {
    assert.equal(feed.shouldAccept(fixture, { lastUpdated: new Date(Date.now() + 1000).toISOString(), deals: [] }, 'github-feed'), true);
});

test('a stalled network request times out instead of blocking every later refresh', async () => {
    await assert.rejects(feed.fetchSnapshot('https://example.test/feed', (_url, { signal }) =>
        new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')))), 15), /aborted/);
});
