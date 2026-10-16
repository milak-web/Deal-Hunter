const { test } = require('node:test');
const assert = require('node:assert/strict');
const { extendedGiveawayPlatform, mapGamerPowerPlatform, preserveFailedProviderDeals } = require('../lib/scrapers');

const offer = { title: 'Collector test fixture', worth: '$19.99', end_date: '2030-01-01 12:00:00' };
test('recognizes real partner store domains', () => {
    for (const [domain, platform] of [['store.ubisoft.com', 'Ubisoft'], ['www.ea.com', 'EA'],
        ['www.fanatical.com', 'Fanatical'], ['www.humblebundle.com', 'Humble'], ['freebies.indiegala.com', 'IndieGala']]) {
        assert.equal(extendedGiveawayPlatform(offer, `https://${domain}/game`).platform, platform);
    }
});
test('partner discovery rejects trials, raffles, subscriptions and paid conditions', () => {
    for (const title of ['Free weekend', 'Free trial', 'Demo', 'Chance to win', 'Raffle', 'Subscription', 'With any purchase']) {
        assert.equal(extendedGiveawayPlatform({ ...offer, title }, 'https://store.ubisoft.com/game'), null);
    }
    assert.equal(extendedGiveawayPlatform({ ...offer, worth: '$0' }, 'https://www.ea.com/game'), null);
    assert.equal(extendedGiveawayPlatform({ ...offer, end_date: 'N/A' }, 'https://freebies.indiegala.com/game'), null);
    assert.equal(extendedGiveawayPlatform(offer, 'https://ea.com.example.test/game'), null);
});
test('Epic mobile offers remain in Mobile rather than disappearing into the PC filter', () => {
    assert.equal(mapGamerPowerPlatform('PC, Android, iOS, Epic Games Store').platform, 'Mobile');
});

test('failed providers retain only dated active offers; successful empty scans remove them', () => {
    const active = { title: 'Cached test fixture', platform: 'Itch.io', link: 'https://fixture.itch.io/game',
        expiryDate: new Date(Date.now() + 60000).toISOString() };
    const expired = { ...active, title: 'Expired fixture', expiryDate: '2020-01-01T00:00:00Z' };
    const unknown = { ...active, title: 'Undated fixture', expiryDate: null };
    const failed = [{ name: 'itch', platforms: ['Itch.io'], failedRequests: 1 }];
    assert.equal(preserveFailedProviderDeals([], [active, expired, unknown], failed).length, 1);
    assert.equal(preserveFailedProviderDeals([], [active], [{ ...failed[0], failedRequests: 0 }]).length, 0);
});
