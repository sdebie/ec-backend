import http from 'k6/http';
import {check, sleep} from 'k6';
import {Trend, Rate} from 'k6/metrics';

// Measures catalogue-browse and checkout latency at fixed, isolated concurrency
// levels — not a continuous ramp. A ramp blends stage-transition noise into the
// numbers (VUs starting/stopping mid-measurement); isolated levels answer the
// actual question, "how does this behave at N concurrent shoppers," cleanly.
//
// Usage: BASE_URL=http://localhost:8080 k6 run capacity_test.js
// Requires a real product catalogue at BASE_URL — setup() fetches real variant
// ids from shoppingProductList rather than assuming any fixture data.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LEVELS = [100, 300, 600, 1000];
const LEVEL_DURATION = '40s';

const metrics = {};
for (const level of LEVELS) {
    metrics[level] = {
        browse: new Trend(`browse_p${level}_duration`, true),
        checkout: new Trend(`checkout_p${level}_duration`, true),
        checkoutOk: new Rate(`checkout_p${level}_ok_rate`),
    };
}

export function setup() {
    const res = http.post(`${BASE_URL}/api/graphql`, JSON.stringify({
        query: `{ shoppingProductList(pageIndex: 0, pageSize: 100) { content { variantId } } }`,
    }), {headers: {'Content-Type': 'application/json'}});

    const variantIds = JSON.parse(res.body).data.shoppingProductList.content
        .map(p => p.variantId)
        .filter(Boolean);

    if (variantIds.length === 0) {
        throw new Error('No variant ids returned by shoppingProductList — seed a catalogue before running this test.');
    }
    return {variantIds};
}

export const options = {
    scenarios: Object.fromEntries(LEVELS.flatMap((level, i) => {
        const start = `${i * 50}s`;
        return [
            [`browse_${level}`, {
                executor: 'constant-vus', exec: 'browse', vus: level,
                duration: LEVEL_DURATION, startTime: start, gracefulStop: '5s',
                env: {LEVEL: String(level)},
            }],
            [`checkout_${level}`, {
                executor: 'constant-vus', exec: 'checkout', vus: Math.max(1, Math.round(level * 0.08)),
                duration: LEVEL_DURATION, startTime: start, gracefulStop: '5s',
                env: {LEVEL: String(level)},
            }],
        ];
    })),
    // The agreed capacity target (Tier D2): 500 concurrent shoppers, checkout
    // p95 under 500ms, error rate under 0.1%. Checked at the 600 level (the
    // smallest tested level above the 500 target).
    thresholds: {
        checkout_p600_duration: ['p(95)<500'],
        checkout_p600_ok_rate: ['rate>0.999'],
    },
};

const CATALOGUE_QUERY = `query($pageIndex: Int, $pageSize: Int, $sortBy: CatalogueSortEn) {
  shoppingProductList(pageIndex: $pageIndex, pageSize: $pageSize, sortBy: $sortBy) {
    totalElements
    content { id name }
  }
}`;
const SORTS = ['NAME_ASC', 'PRICE_ASC', 'PRICE_DESC'];

export function browse() {
    const level = __ENV.LEVEL;
    const pageIndex = Math.floor(Math.random() * 20);
    const sortBy = SORTS[Math.floor(Math.random() * SORTS.length)];
    const res = http.post(`${BASE_URL}/api/graphql`, JSON.stringify({
        query: CATALOGUE_QUERY,
        variables: {pageIndex, pageSize: 24, sortBy},
    }), {headers: {'Content-Type': 'application/json'}, tags: {name: 'browse', level}});
    metrics[level].browse.add(res.timings.duration);
    check(res, {'browse status 200': r => r.status === 200});
    sleep(1 + Math.random() * 2);
}

function randomUUID() {
    // k6's JS runtime has no crypto.randomUUID(); Math.random()-based UUIDv4 is
    // fine here since this only needs to be well-formed and unique, not secure.
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

export function checkout(data) {
    const level = __ENV.LEVEL;
    const variantId = data.variantIds[Math.floor(Math.random() * data.variantIds.length)];
    // A distinct synthetic source IP per attempt simulates many real, distinct
    // shoppers, matching what checkout's 20/hour-per-IP limiter is designed to
    // allow — as opposed to one caller hammering it, which it should still catch.
    const fakeIp = `10.${Math.floor(Math.random() * 255)}.${Math.floor(Math.random() * 255)}.${Math.floor(Math.random() * 255)}`;
    const res = http.post(`${BASE_URL}/api/orders`, JSON.stringify({
        items: [{variantId, quantity: 1}],
    }), {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': randomUUID(),
            'X-Forwarded-For': fakeIp,
        },
        tags: {name: 'checkout', level},
    });
    metrics[level].checkout.add(res.timings.duration);
    // 422 (out of stock) is a correct rejection, not a capacity failure —
    // tracked separately from real errors (5xx/429/timeout).
    metrics[level].checkoutOk.add(res.status === 201 || res.status === 422);
    sleep(2 + Math.random() * 2);
}
