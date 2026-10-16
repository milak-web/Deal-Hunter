(function (root) {
    'use strict';

    function isNativeApp(location, capacitor) {
        return Boolean(capacitor?.isNativePlatform?.()) ||
            /^(capacitor|ionic):$/.test(location.protocol);
    }

    function validatePayload(payload) {
        if (!payload || !Array.isArray(payload.deals)) {
            throw new Error('The feed did not contain a deals snapshot');
        }
        if (!payload.deals.every(deal => deal && typeof deal.title === 'string' &&
            typeof deal.platform === 'string' && /^https?:\/\//i.test(deal.link))) {
            throw new Error('The feed contained an invalid deal');
        }
        return payload;
    }

    async function fetchSnapshot(url, fetcher = fetch, timeoutMs = 12000) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        try {
            const response = await fetcher(url, { cache: 'no-store', signal: controller.signal });
            if (!response.ok) throw new Error(`Deal feed returned HTTP ${response.status}`);
            return validatePayload(await response.json());
        } finally {
            clearTimeout(timer);
        }
    }

    function shouldAccept(current, incoming, source) {
        validatePayload(incoming);
        if (!current?.deals?.length) return true;
        if (source === 'local-bundle' || source === 'static-fallback') return false;
        const before = Date.parse(current.lastUpdated) || 0;
        const after = Date.parse(incoming.lastUpdated) || 0;
        return !before || (after > 0 && after >= before);
    }

    const api = { isNativeApp, validatePayload, fetchSnapshot, shouldAccept };
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.DealHunterFeed = api;
})(typeof globalThis === 'object' ? globalThis : this);
