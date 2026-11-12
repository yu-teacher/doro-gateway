import { defineConfig } from '@playwright/test';

// 한 번에 하나씩: 시험이 IAM 컨테이너를 멈췄다 켜기도 하고 서로의 세션을 건드리지 않게 한다.
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: { trace: 'off' },
});
