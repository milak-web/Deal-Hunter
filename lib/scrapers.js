const axios = require('axios');
const cheerio = require('cheerio');
const { AsyncLocalStorage } = require('node:async_hooks');
const scanContext = new AsyncLocalStorage();

axios.interceptors.response.use(response => response, error => {
    const current = scanContext.getStore();
    if (current) current.failedRequests += 1;
    return Promise.reject(error);
});

const MONTH_INDEX = {
    jan: 0,
    feb: 1,
    mar: 2,
    apr: 3,
    may: 4,
    jun: 5,
    jul: 6,
    aug: 7,
    sep: 8,
    sept: 8,
    oct: 9,
    nov: 10,
    dec: 11
};

const NON_GAME_KEYWORDS = [
    'soundtrack',
    'dlc',
    'expansion',
    'season pass',
    'demo',
    'test server',
    'playtest',
    'avatar',
    'wallpaper'
];

const DEFAULT_HEADERS = {
    'Accept-Language': 'en-US,en;q=0.9',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36'
};

function romanToInt(roman) {
    if (!roman || typeof roman !== 'string') return null;
    const r = roman.toLowerCase();
    if (!/^[ivxlcdm]+$/.test(r)) return null;

    const vals = { i: 1, v: 5, x: 10, l: 50, c: 100, d: 500, m: 1000 };
    let total = 0;
    for (let i = 0; i < r.length; i++) {
        const current = vals[r[i]];
        const next = vals[r[i + 1]] || 0;
        if (!current) return null;
        total += current < next ? -current : current;
    }
    return total > 0 ? total : null;
}

function normalizeLink(raw) {
    if (!raw || typeof raw !== 'string') return null;
    try {
        const u = new URL(raw);
        for (const key of [...u.searchParams.keys()]) {
            if (/^(utm_|snr$)/i.test(key)) u.searchParams.delete(key);
        }
        u.hash = '';
        return u.toString().replace(/\/$/, '');
    } catch (_) {
        return raw.split('?')[0].trim().replace(/\/$/, '');
    }
}

function cleanPriceText(value) {
    if (!value || typeof value !== 'string') return value;
    return value.replace(/Ã‚/g, '').trim();
}

function stripGiveawaySuffix(title) {
    if (!title || typeof title !== 'string') return title;
    return title
        .replace(/\s*\((steam|epic games?|gog|itch\.io|mobile|android|ios)\)\s*(key\s+)?giveaway$/i, '')
        .replace(/\s*(key\s+)?giveaway$/i, '')
        .trim();
}

function hasNonGameSignal(title) {
    if (!title || typeof title !== 'string') return true;
    const t = title.toLowerCase();
    return NON_GAME_KEYWORDS.some(k => t.includes(k));
}

function normalizeDealTitle(value) {
    if (!value || typeof value !== 'string') return '';
    return value
        .toLowerCase()
        .replace(/\((steam|epic games?|gog|itch\.io|mobile|android|ios)\)/gi, '')
        .replace(/\b(key\s+)?giveaway\b/gi, '')
        .replace(/\b([ivxlcdm]+)(?:\s*-\s*([ivxlcdm]+))?\b/gi, (full, a, b) => {
            const left = romanToInt(a);
            if (!left) return full;
            if (!b) return String(left);
            const right = romanToInt(b);
            return right ? `${left}-${right}` : String(left);
        })
        .replace(/\s+/g, ' ')
        .trim();
}

function isLikelyNativeStoreLink(platform, link) {
    if (!link || typeof link !== 'string') return false;
    const p = (platform || '').toLowerCase();
    const l = link.toLowerCase();

    if (p.includes('steam')) return l.includes('store.steampowered.com/app/');
    if (p.includes('epic')) return l.includes('store.epicgames.com/');
    if (p.includes('gog')) return l.includes('gog.com/');
    if (p.includes('itch')) return l.includes('itch.io/');
    if (p.includes('mobile') || p.includes('android') || p.includes('ios') || p.includes('google')) {
        return l.includes('play.google.com/store/apps/details') || l.includes('apps.apple.com/');
    }
    return false;
}

function getDealQualityScore(deal) {
    let score = 0;
    if (isLikelyNativeStoreLink(deal.platform, deal.link)) score += 3;
    if (deal.imageUrl) score += 1;
    if (deal.originalPrice && deal.originalPrice !== 'N/A') score += 1;
    if (deal.expiryDate && isValidDate(deal.expiryDate)) score += 2;
    return score;
}

function isValidDate(value) {
    const d = new Date(value);
    return !Number.isNaN(d.getTime());
}

function finalizeDeals(rawDeals) {
    const now = new Date();
    const bestByIdentity = new Map();
    const unique = [];
    const seenLink = new Set();

    for (const deal of rawDeals || []) {
        if (!deal || !deal.title || !deal.platform || !deal.link) continue;
        if (hasNonGameSignal(deal.title)) continue;

        const normalizedLink = normalizeLink(deal.link);
        if (!normalizedLink || !normalizedLink.startsWith('http')) continue;
        if (deal.expiryDate) {
            if (!isValidDate(deal.expiryDate)) continue;
            if (new Date(deal.expiryDate) <= now) continue;
        }

        const normalizedDeal = {
            ...deal,
            link: normalizedLink
        };

        const identityKey = `${(normalizedDeal.platform || '').toLowerCase()}|${normalizeDealTitle(normalizedDeal.title)}`;
        if (!identityKey.endsWith('|')) {
            const existing = bestByIdentity.get(identityKey);
            if (!existing || getDealQualityScore(normalizedDeal) > getDealQualityScore(existing)) {
                bestByIdentity.set(identityKey, normalizedDeal);
            }
        }
    }

    for (const deal of bestByIdentity.values()) {
        if (seenLink.has(deal.link)) continue;
        unique.push(deal);
        seenLink.add(deal.link);
    }

    return unique;
}

function hasPlatform(deals, platformName) {
    return (deals || []).some(d => (d?.platform || '').toLowerCase().includes(platformName.toLowerCase()));
}

function hasMobileDeals(deals) {
    return (deals || []).some(d => {
        const p = (d?.platform || '').toLowerCase();
        return p.includes('mobile') || p.includes('android') || p.includes('ios') || p.includes('google') || d?.category === 'Mobile';
    });
}

function parseGamerPowerDate(value) {
    if (!value || value === 'N/A') return null;
    const normalized = value.replace(' ', 'T');
    const date = new Date(normalized);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
}


function mapGamerPowerPlatform(rawPlatforms) {
    const p = (rawPlatforms || '').toLowerCase();
    if (p.includes('android') || p.includes('ios') || p.includes('mobile') || p.includes('google play')) {
        return { platform: 'Mobile', category: 'Mobile' };
    }
    if (p.includes('steam')) return { platform: 'Steam', category: 'Game' };
    if (p.includes('gog')) return { platform: 'GOG', category: 'Game' };
    if (p.includes('epic')) return { platform: 'Epic Games', category: 'Game' };
    if (/ubisoft|uplay/.test(p)) return { platform: 'Ubisoft', category: 'Game' };
    if (/\bea\b|origin/.test(p)) return { platform: 'EA', category: 'Game' };
    if (p.includes('humble')) return { platform: 'Humble', category: 'Game' };
    if (p.includes('fanatical')) return { platform: 'Fanatical', category: 'Game' };
    if (p.includes('itch')) return { platform: 'Itch.io', category: 'Game' };
    return null;
}

function extendedGiveawayPlatform(item, resolvedLink) {
    let host;
    try { host = new URL(resolvedLink).hostname.toLowerCase(); } catch (_) { return null; }
    const providers = [
        ['Ubisoft', 'ubisoft.com'], ['EA', 'ea.com'],
        ['Fanatical', 'fanatical.com'], ['Humble', 'humblebundle.com'],
        ['IndieGala', 'indiegala.com']
    ];
    const provider = providers.find(([, domain]) => host === domain || host.endsWith('.' + domain));
    if (!provider) return null;
    const text = `${item.title || ''} ${item.description || ''} ${item.instructions || ''}`;
    if (/free[- ]weekend|free[- ]trial|\bdemo\b|\bbeta\b|raffle|sweepstake|chance to win|subscription|prime gaming|purchase required|with (?:a |any )?purchase/i.test(text)) return null;
    const worth = Number(String(item.worth || '').replace(/[^0-9.]/g, ''));
    if (!(worth > 0) || !parseGamerPowerDate(item.end_date)) return null;
    return { platform: provider[0], category: 'Game' };
}

async function fetchGamerPowerFallbackDeals() {
    try {
        const { data } = await axios.get('https://www.gamerpower.com/api/giveaways?type=game', {
            headers: DEFAULT_HEADERS,
            timeout: 15000
        });
        if (!Array.isArray(data)) return [];

        const mapped = await Promise.all(
            data.map(async item => {
                if ((item.status || '').toLowerCase() !== 'active') return null;
                if (String(item.type || '').toLowerCase() !== 'game') return null;
                const expiryDate = parseGamerPowerDate(item.end_date);
                if (!expiryDate) return null;
                const openLink = item.open_giveaway_url || item.open_giveaway || item.gamerpower_url;
                if (!/^https?:\/\//i.test(openLink || '')) return null;

                let resolvedLink = openLink;
                try {
                    const resolved = await axios.get(openLink, {
                        headers: DEFAULT_HEADERS,
                        timeout: 10000,
                        maxRedirects: 10,
                        validateStatus: () => true
                    });
                    resolvedLink = resolved?.request?.res?.responseUrl || openLink;
                } catch (_) {
                    resolvedLink = openLink;
                }
                const extendedPlatform = extendedGiveawayPlatform(item, resolvedLink);
                let host;
                try { host = new URL(resolvedLink).hostname; } catch (_) { return null; }
                const partnerHost = /(^|\.)(ubisoft\.com|ea\.com|fanatical\.com|humblebundle\.com|indiegala\.com)$/i.test(host);
                if (partnerHost && !extendedPlatform) return null;
                const mappedPlatform = extendedPlatform || mapGamerPowerPlatform(item.platforms);
                if (!mappedPlatform) return null;
                // An explicitly identified partner offer must pass the stricter partner filters.
                if (/^(Ubisoft|EA|Fanatical|Humble|IndieGala)$/.test(mappedPlatform.platform)
                    && !extendedGiveawayPlatform(item, resolvedLink)) return null;
                return {
                    title: stripGiveawaySuffix(item.title),
                    platform: mappedPlatform.platform,
                    originalPrice: item.worth && item.worth !== 'N/A' ? item.worth : 'N/A',
                    link: resolvedLink,
                    category: mappedPlatform.category,
                    expiryDate,
                    imageUrl: item.thumbnail || null,
                    source: 'GamerPower',
                    sourceUrl: item.gamerpower_url || 'https://www.gamerpower.com',
                    requirements: item.instructions || ''
                };
            })
        );

        return finalizeDeals(mapped.filter(Boolean));
    } catch (e) {
        console.error('GamerPower fallback error:', e.message);
        return [];
    }
}


function parseSteamExpiryToIso(rawTimerText) {
    if (!rawTimerText || typeof rawTimerText !== 'string') return null;

    const normalized = rawTimerText.replace(/\s+/g, ' ').trim();
    const lower = normalized.toLowerCase();
    const extracted =
        lower.match(/before (.*?)(?:[.!]|$)/i)?.[1]?.trim() ||
        lower.match(/ends (.*?)(?:[.!]|$)/i)?.[1]?.trim() ||
        lower.match(/offer ends[:\s]+(.*?)(?:[.!]|$)/i)?.[1]?.trim() ||
        lower.match(/during this limited-time promotion.*?(\d{1,2}\s+[a-z]{3,9}.*?(?:am|pm|utc))/i)?.[1]?.trim() ||
        normalized;

    // Patterns seen on Steam:
    // "13 Apr @ 10:00am", "Apr 13 @ 10:00am", "2024 Oct 13 - 8:00pm", "26 June 2024 â€“ 17:00:00 UTC"
    const dayMonthMatch = extracted.match(/^(\d{1,2})\s+([a-z]{3,9})\s*(?:\d{4})?\s*[@-]\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm)$/i);
    const monthDayMatch = extracted.match(/^([a-z]{3,9})\s+(\d{1,2})\s*(?:,?\s*\d{4})?\s*[@-]\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm)$/i);
    const yearMonthDayMatch = extracted.match(/^(\d{4})\s+([a-z]{3,9})\s+(\d{1,2})\s*[@-]\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(am|pm)$/i);
    const utcStyleMatch = extracted.match(/^(\d{1,2})\s+([a-z]{3,9})\s+(\d{4})\s*[â€“-]\s*(\d{1,2}):(\d{2}):(\d{2})\s*utc$/i);

    if (utcStyleMatch) {
        const day = Number(utcStyleMatch[1]);
        const monthToken = utcStyleMatch[2].toLowerCase().slice(0, 4);
        const month = MONTH_INDEX[monthToken] ?? MONTH_INDEX[monthToken.slice(0, 3)];
        const year = Number(utcStyleMatch[3]);
        const hour = Number(utcStyleMatch[4]);
        const minute = Number(utcStyleMatch[5]);
        const second = Number(utcStyleMatch[6]);
        if (month === undefined) return null;
        const date = new Date(Date.UTC(year, month, day, hour, minute, second, 0));
        return Number.isNaN(date.getTime()) ? null : date.toISOString();
    }

    if (yearMonthDayMatch) {
        const year = Number(yearMonthDayMatch[1]);
        const monthToken = yearMonthDayMatch[2].toLowerCase().slice(0, 4);
        const month = MONTH_INDEX[monthToken] ?? MONTH_INDEX[monthToken.slice(0, 3)];
        const day = Number(yearMonthDayMatch[3]);
        const hour12 = Number(yearMonthDayMatch[4]);
        const minute = Number(yearMonthDayMatch[5]);
        const ampm = yearMonthDayMatch[7].toLowerCase();
        if (month === undefined) return null;
        let hour24 = hour12 % 12;
        if (ampm === 'pm') hour24 += 12;
        const candidate = new Date(year, month, day, hour24, minute, 0, 0);
        return Number.isNaN(candidate.getTime()) ? null : candidate.toISOString();
    }

    const match = dayMonthMatch || monthDayMatch;
    if (!match) return null;

    const hasDayFirst = Boolean(dayMonthMatch);
    const day = Number(hasDayFirst ? match[1] : match[2]);
    const monthToken = (hasDayFirst ? match[2] : match[1]).toLowerCase().slice(0, 4);
    const month = MONTH_INDEX[monthToken] ?? MONTH_INDEX[monthToken.slice(0, 3)];
    const hour12 = Number(match[3]);
    const minute = Number(match[4]);
    const ampm = match[6].toLowerCase();

    if (!Number.isInteger(day) || month === undefined || !Number.isInteger(hour12) || !Number.isInteger(minute)) {
        return null;
    }

    let hour24 = hour12 % 12;
    if (ampm === 'pm') hour24 += 12;

    const now = new Date();
    let year = now.getFullYear();
    let candidate = new Date(year, month, day, hour24, minute, 0, 0);

    // Roll to next year only near year boundary (for example Dec -> Jan timers).
    if (candidate.getTime() < now.getTime()) {
        const isYearBoundaryForward = now.getMonth() >= 10 && month <= 1;
        if (isYearBoundaryForward) {
            year += 1;
            candidate = new Date(year, month, day, hour24, minute, 0, 0);
        }
    }

    return Number.isNaN(candidate.getTime()) ? null : candidate.toISOString();
}

function extractSteamExpiryFromPage(appData) {
    if (!appData || typeof appData !== 'string') return null;
    const $ = cheerio.load(appData);

    const directTimers = [
        $('.game_purchase_discount_quantity').text().trim(),
        $('.game_purchase_discount_countdown').text().trim(),
        $('.sale_ends_date').text().trim(),
        $('.discount_block .discount_notice').text().trim()
    ].filter(Boolean);

    for (const timerText of directTimers) {
        const parsed = parseSteamExpiryToIso(timerText);
        if (parsed) return parsed;
    }

    const bodyText = $('body').text().replace(/\s+/g, ' ').trim();
    const textPatterns = [
        /free to keep when you get it before ([^.!\n]+)/i,
        /offer ends[:\s]+([^.!\n]+)/i,
        /(\d{1,2}\s+[a-z]{3,9}\s+\d{4}\s*[â€“-]\s*\d{1,2}:\d{2}:\d{2}\s*utc)/i,
        /([a-z]{3,9}\s+\d{1,2}\s*[,@-]\s*\d{1,2}:\d{2}\s*(?:am|pm))/i,
        /(\d{1,2}\s+[a-z]{3,9}\s*[@-]\s*\d{1,2}:\d{2}\s*(?:am|pm))/i
    ];

    for (const pattern of textPatterns) {
        const m = bodyText.match(pattern);
        if (!m || !m[1]) continue;
        const parsed = parseSteamExpiryToIso(m[1]);
        if (parsed) return parsed;
    }

    return null;
}

function getEpicPromoWindows(promotions) {
    const windows = [];
    const groups = [
        ...(promotions?.promotionalOffers || []),
        ...(promotions?.upcomingPromotionalOffers || [])
    ];
    for (const group of groups) {
        for (const promo of (group?.promotionalOffers || [])) {
            windows.push({
                startDate: promo.startDate || null,
                endDate: promo.endDate || null,
                discountPercentage: promo.discountSetting?.discountPercentage
            });
        }
    }
    return windows;
}

function pickEpicActivePromo(promotions) {
    const now = Date.now();
    const windows = getEpicPromoWindows(promotions)
        .filter(w => w.startDate && w.endDate && isValidDate(w.startDate) && isValidDate(w.endDate))
        .filter(w => new Date(w.startDate).getTime() <= now && new Date(w.endDate).getTime() > now)
        .sort((a, b) => new Date(a.endDate) - new Date(b.endDate));

    return windows[0] || null;
}

function buildEpicDeal(el) {
    const price = el?.price?.totalPrice;
    if (!price || !el?.title) return null;

    const activePromo = pickEpicActivePromo(el.promotions);
    const isCurrentlyFree = price.discountPrice === 0 && price.originalPrice > 0;
    const hasActiveFreePromo = activePromo && activePromo.discountPercentage === 0;
    if (!isCurrentlyFree && !hasActiveFreePromo) return null;

    const productSlug = typeof el.productSlug === 'string' ? el.productSlug.replace(/\/home$/i, '').trim() : null;
    const pageSlug =
        productSlug ||
        el.offerMappings?.[0]?.pageSlug ||
        el.catalogNs?.mappings?.[0]?.pageSlug ||
        el.urlSlug ||
        (el.url ? el.url.split('/').pop() : null);
    if (!pageSlug && !el.url) return null;

    const link = el.url
        ? (el.url.startsWith('http') ? el.url : `https://store.epicgames.com${el.url}`)
        : `https://store.epicgames.com/en-US/p/${pageSlug}`;

    const imageUrl =
        el.keyImages?.find(img => img.type === "Thumbnail")?.url ||
        el.keyImages?.find(img => img.type === "DieselStoreFrontWide")?.url ||
        el.keyImages?.find(img => img.type === "OfferImageTall")?.url ||
        el.keyImages?.[0]?.url ||
        null;

    return {
        title: el.title,
        platform: "Epic Games",
        originalPrice: price?.fmtPrice?.originalPrice || "N/A",
        link,
        category: "Game",
        startDate: activePromo?.startDate || null,
        expiryDate: activePromo?.endDate || null,
        imageUrl
    };
}

async function fetchEpicFreeGames() {
    const deals = [];
    const url = "https://store.epicgames.com/graphql";

    // This GraphQL query mimics the "Browse" page with sorting by price.
    // It captures ALL games that are currently 100% off.
    const query = `
    query searchStoreQuery($allowCountries: String, $category: String, $count: Int, $country: String!, $keywords: String, $locale: String, $namespace: String, $sortBy: String, $sortDir: String, $start: Int, $tag: String, $withPrice: Boolean = false, $onSale: Boolean = false) {
      Catalog {
        searchStore(allowCountries: $allowCountries, category: $category, count: $count, country: $country, keywords: $keywords, locale: $locale, namespace: $namespace, sortBy: $sortBy, sortDir: $sortDir, start: $start, tag: $tag, withPrice: $withPrice, onSale: $onSale) {
          elements {
            title
            keyImages {
              type
              url
            }
            productSlug
            urlSlug
            url
            items {
              id
              namespace
            }
            price {
              totalPrice {
                discountPrice
                originalPrice
                fmtPrice {
                  originalPrice
                  discountPrice
                }
              }
            }
            promotions {
              promotionalOffers {
                promotionalOffers {
                  startDate
                  endDate
                  discountSetting {
                    discountType
                    discountPercentage
                  }
                }
              }
            }
          }
        }
      }
    }`;

    const variables = {
        allowCountries: "US",
        category: "games/edition/base|software/edition/base",
        count: 100, // We take more to ensure we catch all free items
        country: "US",
        locale: "en-US",
        sortBy: "currentPrice",
        sortDir: "ASC",
        withPrice: true,
        onSale: true // This matches the "Discounted" filter in the browse link
    };

    try {
        const { data: response } = await axios.post(url, { query, variables }, {
            headers: {
                'Content-Type': 'application/json',
                ...DEFAULT_HEADERS,
                'Origin': 'https://store.epicgames.com',
                'Referer': 'https://store.epicgames.com/en-US/browse?sortBy=currentPrice&sortDir=ASC&priceTier=tierDiscouted'
            }
        });

        const elements = response?.data?.Catalog?.searchStore?.elements;
        if (elements) {
            elements.forEach(el => {
                const deal = buildEpicDeal(el);
                if (deal) deals.push(deal);
            });
        }
    } catch (e) {
        console.error("Epic Scraper Error:", e.message);
        // Fallback to the static endpoint if GraphQL is blocked
        return fetchEpicStaticFallback();
    }

    return finalizeDeals(deals);
}

async function fetchEpicStaticFallback() {
    const deals = [];
    try {
        const url = "https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions?locale=en-US&country=US&allowCountries=US&count=100";
        const { data: response } = await axios.get(url, {
            headers: DEFAULT_HEADERS
        });

        const elements = response?.data?.Catalog?.searchStore?.elements;
        if (elements) {
            elements.forEach(el => {
                const deal = buildEpicDeal(el);
                if (deal) deals.push(deal);
            });
        }
    } catch (e) {
        console.error("Epic Fallback Error:", e.message);
    }
    return finalizeDeals(deals);
}

async function fetchSteamFreeDeals() {
    const deals = [];
    const pageSize = 50;
    const maxPages = 4;
    const provisionalExpiryHours = 8;

    function collectRowsFromHtml(html, rowAccumulator) {
        const $ = cheerio.load(html || '');
        const selectors = [
            '#search_resultsRows a',
            'a.search_result_row',
            'a[data-ds-appid]',
            'a[data-ds-packageid]'
        ];
        const bucket = [];
        selectors.forEach((selector) => {
            $(selector).each((_, el) => bucket.push($(el)));
        });

        const seen = new Set();
        bucket.forEach((row) => {
            const href = row.attr('href');
            if (!href) return;
            const key = href.split('?')[0];
            if (seen.has(key)) return;
            seen.add(key);
            rowAccumulator.push(row);
        });
    }

    const rowNodes = [];
    try {
        for (let page = 0; page < maxPages; page++) {
            const start = page * pageSize;
            const searchJsonUrl = `https://store.steampowered.com/search/results/?query&start=${start}&count=${pageSize}&specials=1&category1=998&supportedlang=english&cc=us&l=english&infinite=1`;
            const { data } = await axios.get(searchJsonUrl, {
                headers: DEFAULT_HEADERS,
                timeout: 12000
            });

            const html = data?.results_html || '';
            if (!html.trim()) break;
            const before = rowNodes.length;
            collectRowsFromHtml(html, rowNodes);
            if (rowNodes.length === before) break;
        }
    } catch (jsonErr) {
        console.error("Steam JSON Search Scraper Error:", jsonErr.message);
        try {
            const searchUrl = "https://store.steampowered.com/search/?category1=998&specials=1";
            const { data } = await axios.get(searchUrl, {
                headers: DEFAULT_HEADERS,
                timeout: 10000
            });
            collectRowsFromHtml(data, rowNodes);
        } catch (htmlErr) {
            console.error("Steam HTML Search Scraper Error:", htmlErr.message);
        }
    }

    const seen = new Set();
    const dealPromises = [];
    rowNodes.forEach((row) => {
        const discountPct = row.find('.discount_pct').text().trim();
        const finalPrice = row.find('.discount_final_price').text().trim().toLowerCase();
        const isFree = finalPrice.includes('free') || finalPrice.includes('0.00') || finalPrice.includes('$0');
        if (!discountPct.includes('-100%') || !isFree) return;

        const title = row.find('.title').text().trim();
        if (!title || hasNonGameSignal(title)) return;

        const href = row.attr('href');
        if (!href) return;

        const link = href.split('?')[0];
        if (!/\/(app|sub|bundle)\/\d+/.test(link)) return;
        if (seen.has(link)) return;
        seen.add(link);

        const originalPrice = cleanPriceText(row.find('.discount_original_price').text().trim()) || "N/A";
        const appId = link.match(/\/app\/(\d+)/)?.[1];
        const imageUrl = appId
            ? `https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/${appId}/header.jpg`
            : (row.find('img').attr('src') || null);
        const inlineTimerText = row.find('.search_price_discount_combined').text().trim();

        dealPromises.push((async () => {
            let expiryDate = parseSteamExpiryToIso(inlineTimerText);
            try {
                const { data: appData } = await axios.get(link, {
                    headers: DEFAULT_HEADERS,
                    timeout: 6000
                });
                const pageExpiry = extractSteamExpiryFromPage(appData);
                if (pageExpiry) expiryDate = pageExpiry;
            } catch (e) {
                console.error(`Error fetching Steam timer for ${title}:`, e.message);
            }

            if (!expiryDate || !isValidDate(expiryDate)) {
                expiryDate = new Date(Date.now() + provisionalExpiryHours * 60 * 60 * 1000).toISOString();
            }

            return { title, platform: "Steam", originalPrice, link, category: "Game", imageUrl, expiryDate };
        })());
    });

    const searchDeals = await Promise.all(dealPromises);
    deals.push(...searchDeals.filter(Boolean));

    return finalizeDeals(deals);
}

async function fetchGOGDeals() {
    const deals = [];

    // 1. Check for active giveaways on the home page (Most accurate for limited time)
    try {
        const { data: homeData } = await axios.get("https://www.gog.com/en/", { timeout: 12000 });
        const $home = cheerio.load(homeData);

        $home('a[href*="giveaway"]').each((i, el) => {
            const href = $home(el).attr('href');
            if (!href) return;
            const link = href.startsWith('http') ? href : "https://www.gog.com" + href;

            // Try to find a title in the giveaway banner
            const title = $home(el).find('.giveaway-banner__title, .giveaway-banner__game-title, h2, h3').first().text().trim();

            if (title) {
                deals.push({
                    title: title,
                    platform: "GOG Giveaway",
                    originalPrice: "N/A",
                    link,
                    category: "Game",
                    imageUrl: null
                });
            }
        });
    } catch (e) {
        console.error("GOG Home Scraper Error:", e.message);
    }

    // 2. Check the catalog specifically for 100% off deals
    const url = "https://www.gog.com/en/games?onSale=true&order=desc:updated";
    try {
        const { data } = await axios.get(url, { timeout: 12000 });
        const $ = cheerio.load(data);

        // GOG uses .product-tile as a link or inside it
        $('a.product-tile, product-tile a, a[href*="/game/"]').each((i, el) => {
            const tileLink = $(el);
            const tile = tileLink.closest('product-tile').length ? tileLink.closest('product-tile') : tileLink;

            // Strictly check for -100% discount badge
            const discount = tile.find('.product-tile__discount, .price-discount').text().trim();
            if (discount !== '-100%') return;

            // Double check price - it must be FREE
            const finalPrice = tile.find('.product-tile__price-final, .price-final, .product-tile__price-discounted').text().trim().toUpperCase();
            if (finalPrice && !finalPrice.includes('FREE') && !finalPrice.includes('0.00')) return;

            const title = tile.find('.product-tile__title, .product-title, .title').first().text().trim();
            if (!title) return;

            const originalPrice = tile.find('.product-tile__price-original, .price-old').first().text().trim() || "N/A";
            const href = tileLink.attr('href') || tile.attr('href');
            if (!href) return;
            const link = href.startsWith('http') ? href : "https://www.gog.com" + href;

            // Improved GOG Image Extraction
            let imageUrl = tile.find('source').first().attr('srcset')?.split(',')[0].split(' ')[0] ||
                           tile.find('img').attr('data-src') ||
                           tile.find('img').attr('src');

            if (imageUrl && imageUrl.startsWith('//')) imageUrl = 'https:' + imageUrl;
            // Filter out base64 placeholders or trackers
            if (imageUrl && (imageUrl.startsWith('data:image') || imageUrl.includes('pixel'))) imageUrl = null;

            deals.push({
                title,
                platform: "GOG",
                originalPrice,
                link,
                category: "Game",
                imageUrl
            });
        });

        return finalizeDeals(deals);
    } catch (e) {
        console.error("GOG Scraper Error:", e.message);
        return finalizeDeals(deals);
    }
}

async function fetchMobileDeals() {
    // Google Play HTML is large and can exhaust memory in constrained runtimes.
    // Keep this direct scraper opt-in; default mobile coverage comes from fallback feeds.
    if (process.env.ENABLE_MOBILE_EXPERIMENTAL !== 'true') {
        return [];
    }

    const deals = [];

    // We target official Google Play Store search results for 100% off games.
    // This uses a direct search URL which is more stable than specific collection clusters.
    const searchUrls = [
        "https://play.google.com/store/search?q=free%20games&c=apps",
        "https://play.google.com/store/games"
    ];

    for (const url of searchUrls) {
        try {
            const { data } = await axios.get(url, {
                headers: {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                    'Accept-Language': 'en-US,en;q=0.9',
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
                    'Referer': 'https://play.google.com/'
                },
                timeout: 10000
            });
            const $ = cheerio.load(data);

            // Search result and grid item selectors for Google Play Store
            $('a[href*="/store/apps/details"]').each((i, el) => {
                const relativeLink = $(el).attr('href');
                if (!relativeLink) return;

                const link = relativeLink.startsWith('http') ? relativeLink : "https://play.google.com" + relativeLink;

                // Find title and image relative to the link
                const parent = $(el).closest('.ULeU3b, .VfPpkd-LgbsSe, div[role="listitem"]');
                const title = $(el).find('.vP6uB, .nn9vS').text().trim() ||
                             $(el).attr('aria-label') ||
                             parent.find('.vP6uB, .nn9vS').text().trim();

                if (!title) return;
                if (deals.find(d => d.link === link)) return;

                const imageUrl = parent.find('img').attr('data-src') || parent.find('img').attr('src');

                deals.push({
                    title: title.replace('Install', '').trim(),
                    platform: "Google Play",
                    originalPrice: "FREE",
                    link,
                    category: "Mobile",
                    imageUrl: imageUrl || "https://upload.wikimedia.org/wikipedia/commons/d/d0/Google_Play_Arrow_logo.svg"
                });
            });
        } catch (e) {
            console.error(`Mobile Scraper Error (${url}):`, e.message);
        }
    }

    return finalizeDeals(deals);
}

async function fetchItchSaleExpiry(saleLink, saleExpiryCache) {
    if (!saleLink) return null;
    if (saleExpiryCache.has(saleLink)) return saleExpiryCache.get(saleLink);

    try {
        const { data } = await axios.get(saleLink, {
            headers: DEFAULT_HEADERS,
            timeout: 10000
        });
        const bodyText = cheerio.load(data)('body').text().replace(/\s+/g, ' ');
        const isoMatch = bodyText.match(/(?:Ends|Offer ends)\s+(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?Z)/i)?.[1] || null;
        const expiryDate = isoMatch && isValidDate(isoMatch) ? new Date(isoMatch).toISOString() : null;
        saleExpiryCache.set(saleLink, expiryDate);
        return expiryDate;
    } catch (_) {
        saleExpiryCache.set(saleLink, null);
        return null;
    }
}

async function fetchItchDeals() {
    const deals = [];
    const candidates = [];
    const saleExpiryCache = new Map();
    const maxPages = Number(process.env.ITCH_MAX_PAGES || 3);

    for (let page = 1; page <= maxPages; page++) {
        const url = page === 1 ? 'https://itch.io/games/on-sale' : `https://itch.io/games/on-sale?page=${page}`;
        try {
            const { data } = await axios.get(url, {
                headers: DEFAULT_HEADERS,
                timeout: 15000
            });
            const $ = cheerio.load(data);
            const cards = $('.game_cell');
            if (cards.length === 0) break;

            cards.each((_, el) => {
                const card = $(el);
                const title = card.find('.title').first().text().trim();
                if (!title || hasNonGameSignal(title)) return;

                const discount = card.find('.sale_tag,.discount,.on_sale').first().text().trim();
                if (!discount.includes('-100%')) return;

                const finalPrice = card.find('.price_value,.price').first().text().replace(/\s+/g, ' ').trim().toLowerCase();
                const isFree =
                    finalPrice.includes('free') ||
                    finalPrice.includes('$0') ||
                    finalPrice.includes('0$') ||
                    finalPrice.includes('â‚¬0') ||
                    /(^|[^0-9])0([.,]00)?([^0-9]|$)/.test(finalPrice);
                if (!isFree) return;

                const href = card.find('a.title.game_link, a.game_link').first().attr('href');
                if (!href) return;
                const link = href.startsWith('http') ? href : `https://itch.io${href.startsWith('/') ? '' : '/'}${href}`;

                const saleHref = card.find('a.price_tag.sale').first().attr('href');
                const saleLink = saleHref
                    ? (saleHref.startsWith('http') ? saleHref : `https://itch.io${saleHref.startsWith('/') ? '' : '/'}${saleHref}`)
                    : null;

                let imageUrl =
                    card.find('img').first().attr('data-lazy_src') ||
                    card.find('img').first().attr('data-src') ||
                    card.find('img').first().attr('src') ||
                    null;
                if (imageUrl && imageUrl.startsWith('//')) imageUrl = `https:${imageUrl}`;
                if (imageUrl && imageUrl.startsWith('/')) imageUrl = `https://itch.io${imageUrl}`;

                candidates.push({
                    title,
                    link,
                    saleLink,
                    imageUrl
                });
            });
        } catch (e) {
            console.error(`Itch Scraper Error (page ${page}):`, e.message);
        }
    }

    for (const item of candidates) {
        const expiryDate = await fetchItchSaleExpiry(item.saleLink, saleExpiryCache);
        deals.push({
            title: item.title,
            platform: 'Itch.io',
            originalPrice: 'N/A',
            link: item.link,
            category: 'Game',
            expiryDate,
            imageUrl: item.imageUrl
        });
    }

    return finalizeDeals(deals);
}

async function searchGamePrices(query) {
    if (!query || query.length < 2) return [];

    try {
        const searchUrl = `https://www.cheapshark.com/api/1.0/games?title=${encodeURIComponent(query)}&limit=10`;
        const { data: games } = await axios.get(searchUrl, { timeout: 10000 });
        if (!games || games.length === 0) return [];

        const storeMap = {
            "1": "Steam",
            "2": "GamersGate",
            "3": "GreenManGaming",
            "7": "GOG",
            "11": "Humble Store",
            "13": "Ubisoft Connect",
            "15": "Fanatical",
            "21": "WinGameStore",
            "23": "GameBillet",
            "25": "Epic Games Store",
            "27": "Gamesplanet",
            "28": "Gamesload",
            "29": "2Game",
            "30": "IndieGala",
            "35": "DreamGame"
        };

        const topGames = games.slice(0, 6);
        const results = [];
        for (const game of topGames) {
            try {
                const infoUrl = `https://www.cheapshark.com/api/1.0/games?id=${game.gameID}`;
                const { data: info } = await axios.get(infoUrl, { timeout: 10000 });
                if (!info || !Array.isArray(info.deals)) continue;

                const deals = info.deals
                    .map(d => ({
                        store: storeMap[d.storeID] || "Other Authorized Store",
                        price: `$${d.price}`,
                        retailPrice: `$${d.retailPrice}`,
                        savings: `${Math.round(parseFloat(d.savings || 0))}%`,
                        link: `https://www.cheapshark.com/redirect?dealID=${d.dealID}`
                    }))
                    .sort((a, b) => parseFloat(a.price.replace('$', '')) - parseFloat(b.price.replace('$', '')));

                results.push({
                    gameID: game.gameID,
                    title: game.external,
                    imageUrl: game.thumb,
                    cheapestPriceEver: `$${info.cheapestPriceEver?.price || 'N/A'}`,
                    deals
                });
            } catch (e) {
                console.error(`Error fetching info for game ${game.gameID}:`, e.message);
            }
        }

        return results;
    } catch (e) {
        console.error("Search Error:", e.message);
        return [];
    }
}

function preserveFailedProviderDeals(deals, previousDeals, health) {
    const now = Date.now();
    const retained = (previousDeals || []).filter(deal => {
        if (!deal?.expiryDate || !(Date.parse(deal.expiryDate) > now)) return false;
        return health.some(provider => provider.failedRequests > 0 && (
            provider.platforms.some(platform => String(deal.platform).toLowerCase().startsWith(platform.toLowerCase())) ||
            (provider.name === 'supplemental' && deal.source === 'GamerPower')
        ));
    }).map(deal => ({ ...deal, verificationStatus: 'cached-after-provider-error' }));
    return finalizeDeals([...deals, ...retained]);
}

async function getAllDeals({ previousDeals = [] } = {}) {
    const providers = [
        { name: 'epic', platforms: ['Epic Games'], collect: fetchEpicFreeGames },
        { name: 'steam', platforms: ['Steam'], collect: fetchSteamFreeDeals },
        { name: 'gog', platforms: ['GOG'], collect: fetchGOGDeals },
        { name: 'itch', platforms: ['Itch.io'], collect: fetchItchDeals },
        { name: 'supplemental', platforms: ['Mobile', 'Ubisoft', 'EA', 'Fanatical', 'Humble', 'IndieGala'], collect: fetchGamerPowerFallbackDeals }
    ];

    if (process.env.ENABLE_MOBILE_EXPERIMENTAL === 'true') {
        providers.push({ name: 'mobile', platforms: ['Mobile', 'Google Play'], collect: fetchMobileDeals });
    }

    const health = providers.map(provider => ({ name: provider.name, platforms: provider.platforms, failedRequests: 0 }));
    const scanTasks = providers.map((provider, i) => scanContext.run(health[i], provider.collect));
    const results = await Promise.allSettled(scanTasks);
    results.forEach((result, i) => {
        if (result.status === 'rejected') health[i].failedRequests += 1;
    });
    const mergedDeals = finalizeDeals(results.flatMap(r => r.status === 'fulfilled' ? r.value : []));
    return preserveFailedProviderDeals(mergedDeals, previousDeals, health);
}

module.exports = { getAllDeals, searchGamePrices, fetchSteamFreeDeals, fetchEpicFreeGames, fetchGOGDeals, fetchMobileDeals, fetchItchDeals, extendedGiveawayPlatform, mapGamerPowerPlatform, preserveFailedProviderDeals };
