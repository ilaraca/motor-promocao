import http from 'k6/http';
import { check } from 'k6';

// Usage:
//   JWT_TOKEN="<token>" k6 run scripts/k6-evaluate-load.js
export const options = {
  vus: 10,
  duration: '20s',
  thresholds: {
    http_req_duration: ['p(99)<80'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_TOKEN = __ENV.JWT_TOKEN || '';

export default function () {
  const payload = JSON.stringify({
    userId: 'usr_98765',
    grossAmount: 180.0,
    eligibleAmount: 160.0,
    items: [{ sku: 'ITEM-01', category: 'RESTAURANTS', amount: 160.0 }],
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${JWT_TOKEN}`,
      'X-Correlation-Id': `k6-${__VU}-${__ITER}`,
    },
  };

  const res = http.post(`${BASE_URL}/v1/promotions/evaluate`, payload, params);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'has evaluationId': (r) => r.json('evaluationId') !== undefined,
  });
}
