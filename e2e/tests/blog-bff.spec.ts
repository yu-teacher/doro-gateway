import { test, expect, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

/**
 * 진짜 IAM + 진짜 포털 로그인 화면 + 진짜 블로그 백엔드(SDK BFF) + 진짜 Chromium 으로 확인하는 로그인 흐름.
 * 가짜 IAM 을 쓰는 단위·통합 시험이 못 보는 것(클라이언트 등록, redirect_uri 정확 일치, PKCE, cid 클레임, 쿠키 Path, 토큰 갱신)을 본다.
 */
const state = JSON.parse(readFileSync(new URL('../.state/e2e.json', import.meta.url), 'utf8')) as {
  mode: 'gateway' | 'embedded'; iam: string; blog: string; blogDirect: string; bff: string; portal: string;
  userEmail: string; userPassword: string; adminEmail: string; adminPassword: string;
};
const GATEWAY = state.mode === 'gateway';
const BLOG = state.blog;
const CSRF = { 'X-Blog-Csrf': '1' };
const SESSION_COOKIE = 'doro_blog_session';
const LOGIN_COOKIE = 'doro_blog_login';

type SessionBody = { data: { authenticated: boolean; role: string | null } };

async function sessionOf(ctx: BrowserContext): Promise<SessionBody['data']> {
  const res = await ctx.request.get(`${BLOG}/api/v1/bff/session`);
  expect(res.status(), '세션 조회는 로그인 여부와 무관하게 200').toBe(200);
  return ((await res.json()) as SessionBody).data;
}

async function cookieNamed(ctx: BrowserContext, name: string) {
  return (await ctx.cookies(BLOG)).find((c) => c.name === name);
}

/** 포털 로그인 화면에서 시험 계정으로 로그인한다(이메일 → 다음 → 비밀번호 → 로그인). */
async function fillPortalLogin(page: Page) {
  await page.getByPlaceholder('user@doro.local').fill(state.userEmail);
  await page.getByRole('button', { name: '다음' }).click();
  await page.getByPlaceholder('••••••••').fill(state.userPassword);
  await page.getByRole('button', { name: '로그인', exact: true }).click();
}

/** 블로그 로그인 시작 → 도로(포털) 로그인 → 콜백 → 세션 쿠키. 돌아온 최종 URL 을 반환한다. */
async function loginThroughBlog(page: Page, returnPath = '/after-login'): Promise<string> {
  await page.goto(`${BLOG}/api/v1/bff/login?return=${encodeURIComponent(returnPath)}`);
  await page.waitForURL(/\/login/, { timeout: 30_000 });
  await fillPortalLogin(page);
  await page.waitForURL((url) => url.origin === BLOG, { timeout: 45_000 });
  return page.url();
}

function sh(cmd: string) {
  execSync(cmd, { stdio: 'pipe' });
}

async function waitIamHealthy(timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${state.iam}/actuator/health`);
      if (res.ok) return;
    } catch { /* 아직 기동 중 */ }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error('IAM 이 다시 올라오지 않았다');
}

async function newLoggedInContext(browser: Browser): Promise<BrowserContext> {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await loginThroughBlog(page);
  return ctx;
}

test.describe.configure({ mode: 'serial' });

test('로그인: 블로그 → 도로 로그인 → 콜백 → 세션 쿠키(HttpOnly)와 원래 경로 복귀, 새로고침해도 유지', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  const finalUrl = await loginThroughBlog(page, '/after-login');

  expect(new URL(finalUrl).pathname, '요청한 복귀 경로로 돌아온다').toBe('/after-login');
  const session = await cookieNamed(ctx, SESSION_COOKIE);
  expect(session, '세션 쿠키가 발급된다').toBeTruthy();
  expect(session!.httpOnly, 'JavaScript 가 볼 수 없다').toBe(true);
  expect(session!.sameSite).toBe('Lax');
  expect(await cookieNamed(ctx, LOGIN_COOKIE), '로그인 진행 쿠키는 지워졌다').toBeUndefined();
  expect((await sessionOf(ctx)).authenticated).toBe(true);

  await page.reload();
  expect((await sessionOf(ctx)).authenticated, '새로고침 뒤에도 로그인 상태').toBe(true);
  await ctx.close();
});

test('토큰 만료: 액세스 토큰이 만료돼도 진짜 IAM 에서 갱신해 같은 쿠키로 로그인 상태가 이어진다', async ({ browser }) => {
  const ctx = await newLoggedInContext(browser);
  const before = (await cookieNamed(ctx, SESSION_COOKIE))!.value;

  await new Promise((r) => setTimeout(r, 28_000));   // 액세스 토큰 수명 20초 + 시계 오차 허용 5초를 넘긴다

  expect((await sessionOf(ctx)).authenticated).toBe(true);
  expect((await cookieNamed(ctx, SESSION_COOKIE))!.value, '쿠키 값은 바뀌지 않는다(갱신은 서버 안에서만)').toBe(before);
  await ctx.close();
});

test('IAM 장애: IAM 이 죽은 사이 토큰이 만료돼도 로그아웃되지 않고, IAM 이 돌아오면 같은 쿠키로 다시 로그인 상태다', async ({ browser }) => {
  const ctx = await newLoggedInContext(browser);
  try {
    await new Promise((r) => setTimeout(r, 28_000));   // 액세스 토큰 만료
    sh('docker stop e2e-auth');

    const during = await ctx.request.get(`${BLOG}/api/v1/bff/session`);
    expect(during.status(), 'IAM 이 죽어도 서버 오류가 아니다').toBe(200);
    expect(((await during.json()) as SessionBody).data.authenticated, '이번 요청만 익명').toBe(false);
    expect(await cookieNamed(ctx, SESSION_COOKIE), '브라우저 쿠키는 그대로').toBeTruthy();
  } finally {
    sh('docker start e2e-auth');
  }
  await waitIamHealthy();

  // IAM 이 막 올라온 직후에는 서비스가 IAM 주소를 다시 찾는 데 잠깐 걸릴 수 있다(컨테이너 주소가 바뀐 경우). 다시 로그인하지 않고 곧 복구되는지 본다.
  await expect.poll(async () => (await sessionOf(ctx)).authenticated, {
    timeout: 60_000, intervals: [2_000], message: '복구 뒤 다시 로그인하지 않아도 로그인 상태',
  }).toBe(true);
  await ctx.close();
});

test('로그인 CSRF: 다른 브라우저가 가로채 연 콜백은 거부되고 세션이 생기지 않는다', async ({ browser }) => {
  // 피해자 브라우저가 로그인을 끝까지 진행하되, 마지막 콜백 요청은 보내지 않고 URL 만 잡는다(공격자가 이 URL 을 손에 넣었다고 가정)
  const victim = await browser.newContext();
  const vpage = await victim.newPage();
  let callbackUrl = '';
  await vpage.route('**/api/v1/bff/callback**', async (route) => {
    callbackUrl = route.request().url();
    await route.abort();
  });
  await vpage.goto(`${BLOG}/api/v1/bff/login`);
  await vpage.waitForURL(/\/login/, { timeout: 30_000 });
  await fillPortalLogin(vpage);
  await expect.poll(() => callbackUrl, { timeout: 45_000 }).toContain('/api/v1/bff/callback');
  await victim.close();

  // 다른 브라우저(로그인 진행 쿠키 없음)에서 그 콜백 주소를 연다
  const attacker = await browser.newContext();
  const apage = await attacker.newPage();
  await apage.goto(callbackUrl);

  expect(apage.url()).toContain('login_error=failed');
  expect(await cookieNamed(attacker, SESSION_COOKIE), '세션 쿠키가 발급되지 않는다').toBeUndefined();
  expect((await sessionOf(attacker)).authenticated).toBe(false);
  await attacker.close();
});

test('실패한 콜백은 이미 로그인한 사용자의 세션을 건드리지 않는다', async ({ browser }) => {
  const ctx = await newLoggedInContext(browser);
  const page = await ctx.newPage();

  await page.goto(`${BLOG}/api/v1/bff/callback?code=forged&state=forged`);

  expect(page.url()).toContain('login_error=failed');
  expect((await sessionOf(ctx)).authenticated, '가짜 콜백 링크 하나로 로그아웃되지 않는다').toBe(true);
  await ctx.close();
});

test('쿠키로 상태를 바꾸는 요청은 CSRF 헤더가 있어야 하고, 로그아웃하면 세션이 끝난다', async ({ browser }) => {
  const ctx = await newLoggedInContext(browser);

  const noHeader = await ctx.request.post(`${BLOG}/api/v1/bff/logout`);
  expect(noHeader.status(), 'CSRF 헤더 없이는 로그아웃도 거부').toBe(403);
  expect((await sessionOf(ctx)).authenticated).toBe(true);

  const ok = await ctx.request.post(`${BLOG}/api/v1/bff/logout`, { headers: CSRF });
  expect(ok.status()).toBe(200);
  expect((await sessionOf(ctx)).authenticated, '로그아웃 뒤에는 익명').toBe(false);
  await ctx.close();
});

/**
 * IAM 이 가진 이 시험 계정의 **블로그(OAuth 클라이언트 doro-blog) 활성 세션** 수(IAM DB 를 직접 본다).
 * 포털 화면 로그인이 만드는 포털 자체 세션과 준비 단계의 세션은 세지 않는다.
 */
function activeIamSessions(): number {
  const out = execSync(
    `docker exec e2e-postgres psql -U doro_admin -d doro_auth -tAc "select count(*) from user_sessions s join users u on u.id = s.user_id where u.email = '${state.userEmail}' and s.is_active and s.user_agent = 'OAuth2 PKCE Flow: doro-blog'"`,
    { stdio: 'pipe' },
  ).toString().trim();
  return Number(out);
}

/**
 * 이 시험 계정의 블로그 IAM 세션을 모두 끝내 깨끗한 상태로 만든다. 서비스별 기기 수 상한(3) 덕분에 앞선 시험이 만든 세션이
 * 남아 있으므로, 세션 수를 세는 시험은 시작할 때 비운다.
 */
function resetBlogIamSessions() {
  execSync(
    `docker exec e2e-postgres psql -U doro_admin -d doro_auth -c "update user_sessions set is_active = false where user_agent = 'OAuth2 PKCE Flow: doro-blog' and user_id = (select id from users where email = '${state.userEmail}')"`,
    { stdio: 'pipe' },
  );
}

test('로그아웃은 도로(IAM) 세션도 끝낸다: IAM 의 활성 세션이 사라지고, 훔쳐 둔 쿠키로도 로그인되지 않는다', async ({ browser }) => {
  resetBlogIamSessions();
  const ctx = await newLoggedInContext(browser);
  const stolen = (await cookieNamed(ctx, SESSION_COOKIE))!.value;
  expect(activeIamSessions(), '로그인 중에는 IAM 에 블로그 세션이 하나 있다').toBe(1);

  await ctx.request.post(`${BLOG}/api/v1/bff/logout`, { headers: CSRF });

  expect(activeIamSessions(), 'IAM 의 블로그 세션이 폐기됐다(블로그만 지운 것이 아니다)').toBe(0);
  const thief = await browser.newContext();
  await thief.addCookies([{ name: SESSION_COOKIE, value: stolen, url: BLOG }]);
  expect((await sessionOf(thief)).authenticated, '훔쳐 둔 쿠키로는 익명').toBe(false);
  await thief.close();
  await ctx.close();
});

function psql(sql: string): string {
  return execSync(`docker exec e2e-postgres psql -U doro_admin -d doro_auth -tAc "${sql}"`, { stdio: 'pipe' }).toString().trim();
}

test('서비스별 기기 3개: 같은 사용자가 3개 기기로 로그인해 둘 수 있고, 4번째가 오면 가장 오래 쓰지 않은 기기만 로그아웃된다', async ({ browser }) => {
  resetBlogIamSessions();
  const devices: BrowserContext[] = [];
  for (let i = 0; i < 3; i++) {
    devices.push(await newLoggedInContext(browser));
  }
  for (const [i, ctx] of devices.entries()) {
    expect((await sessionOf(ctx)).authenticated, `기기 ${i + 1} 은 로그인 상태`).toBe(true);
  }
  expect(activeIamSessions(), '같은 서비스에 IAM 세션이 3개').toBe(3);

  // 두 번째로 만든 기기를 "가장 오래 쓰지 않은" 기기로 만든다(만든 순서가 아니라 마지막 사용 기준임을 보려는 것)
  psql(`update user_sessions set last_active_at = now() - interval '1 hour' where id = (select s.id from user_sessions s join users u on u.id = s.user_id where u.email = '${state.userEmail}' and s.is_active and s.user_agent = 'OAuth2 PKCE Flow: doro-blog' order by s.created_at offset 1 limit 1)`);

  const fourth = await newLoggedInContext(browser);
  expect(activeIamSessions(), '4번째가 와도 여전히 3개').toBe(3);

  // 액세스 토큰이 만료되면(20초) 각 서비스 세션이 IAM 에서 갱신을 시도한다: 종료된 세션만 로그인 상태가 풀린다
  await new Promise((r) => setTimeout(r, 28_000));
  expect((await sessionOf(devices[0])).authenticated, '처음 만들었지만 최근에 쓴 기기는 유지').toBe(true);
  expect((await sessionOf(devices[1])).authenticated, '가장 오래 쓰지 않은 기기만 로그아웃').toBe(false);
  expect((await sessionOf(devices[2])).authenticated).toBe(true);
  expect((await sessionOf(fourth)).authenticated, '새 기기는 로그인 상태').toBe(true);

  for (const ctx of [...devices, fourth]) await ctx.close();
});

/** 관리자로 IAM 에 로그인해 액세스 토큰을 얻는다(관리자 API 호출용). */
async function adminToken(): Promise<string> {
  const res = await fetch(`${state.iam}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: state.adminEmail, password: state.adminPassword, deviceInfo: 'e2e-admin' }),
  });
  expect(res.status, '관리자 로그인').toBe(200);
  return ((await res.json()) as { data: { tokens: { accessToken: string } } }).data.tokens.accessToken;
}

async function setSuspension(userId: string, suspended: boolean) {
  const res = await fetch(`${state.iam}/api/v1/admin/users/${userId}/suspension`, {
    method: suspended ? 'PUT' : 'DELETE',
    headers: { Authorization: `Bearer ${await adminToken()}`, 'Content-Type': 'application/json' },
    body: suspended ? JSON.stringify({ reason: 'e2e' }) : undefined,
  });
  expect(res.status, suspended ? '정지' : '정지 해제').toBe(200);
}

test('API 키: 소유자 계정이 정지되면 키가 막히고, 정지가 풀리면 같은 키가 다시 통한다', async ({ browser, request }) => {
  // 로그인한 사용자가 API 키를 발급한다(블로그가 IAM 내부 API 로 계정 상태를 확인한다)
  const ctx = await newLoggedInContext(browser);
  const created = await ctx.request.post(`${BLOG}/api/v1/api-keys`, { headers: CSRF, data: { name: 'e2e-key', expireDays: 1 } });
  expect(created.status()).toBe(200);
  const key = ((await created.json()) as { data: { apiKey: string } }).data.apiKey;
  const userId = psql(`select id from users where email = '${state.userEmail}'`);
  const callWithKey = async () => (await request.get(`${BLOG}/api/v1/posts/me`, { headers: { 'X-API-Key': key } })).status();

  expect(await callWithKey(), '정상 계정의 키는 통한다').toBe(200);

  await setSuspension(userId, true);
  await new Promise((r) => setTimeout(r, 3_000));              // 정상 판정 캐시(2초)가 끝나기를 기다린다
  expect(await callWithKey(), '정지된 계정의 키는 거부된다').toBe(403);

  await setSuspension(userId, false);
  await new Promise((r) => setTimeout(r, 2_000));              // 비정상 판정 캐시(1초)가 끝나기를 기다린다
  expect(await callWithKey(), '정지가 풀리면 같은 키가 다시 통한다(키를 폐기하지 않았다)').toBe(200);
  await ctx.close();
});
// ---------------------------------------------------------------------------------------------------------------------
// 게이트웨이 모드 전용: 로그인·세션을 서비스 밖의 BFF 가 맡고, nginx 가 쿠키를 Bearer 로 바꿔 서비스에 넘긴다.
// ---------------------------------------------------------------------------------------------------------------------

test.describe('게이트웨이 모드', () => {
  test.skip(!GATEWAY, 'E2E_BFF_MODE=gateway 에서만 실행');

  test('서비스는 쿠키를 모른다: 같은 쿠키를 블로그 백엔드에 직접 보내면 익명이고, 게이트웨이를 거쳐야 로그인 상태다', async ({ browser, request }) => {
    const ctx = await newLoggedInContext(browser);
    const cookie = (await cookieNamed(ctx, SESSION_COOKIE))!;

    expect((await sessionOf(ctx)).authenticated, '게이트웨이를 거치면 로그인 상태').toBe(true);

    const direct = await request.get(`${state.blogDirect}/api/v1/bff/session`, { headers: { Cookie: `${cookie.name}=${cookie.value}` } });
    expect(direct.status()).toBe(200);
    expect(((await direct.json()) as SessionBody).data.authenticated, '백엔드는 쿠키를 해석하지 않는다(Bearer 만 받는다)').toBe(false);
    await ctx.close();
  });

  test('클라이언트가 보낸 Authorization 은 쿠키가 있으면 BFF 의 토큰으로 덮어쓰인다(위조 Bearer 로 인증을 흔들 수 없다)', async ({ browser }) => {
    const ctx = await newLoggedInContext(browser);
    const res = await ctx.request.get(`${BLOG}/api/v1/bff/session`, { headers: { Authorization: 'Bearer forged.token.value' } });
    expect(res.status()).toBe(200);
    expect(((await res.json()) as SessionBody).data.authenticated, '위조 헤더 대신 BFF 가 붙인 진짜 토큰으로 인증된다').toBe(true);
    await ctx.close();
  });

  test('쿠키로 상태를 바꾸는 서비스 API 도 CSRF 헤더가 없으면 게이트웨이에서 403 이다(서비스에 닿지 않는다)', async ({ browser }) => {
    const ctx = await newLoggedInContext(browser);

    const noHeader = await ctx.request.post(`${BLOG}/api/v1/api-keys`, { data: { name: 'csrf-probe', expireDays: 1 } });
    expect(noHeader.status()).toBe(403);
    expect(((await noHeader.json()) as { code: string }).code).toBe('AUTH-403-03');

    const withHeader = await ctx.request.post(`${BLOG}/api/v1/api-keys`, { headers: CSRF, data: { name: 'csrf-probe', expireDays: 1 } });
    expect(withHeader.status()).toBe(200);
    await ctx.close();
  });

  test('BFF 장애: 쿠키 없는 공개 요청은 그대로 되고, 쿠키가 있는 요청만 503 이며, 복구되면 같은 쿠키로 다시 로그인 상태다', async ({ browser, request }) => {
    const ctx = await newLoggedInContext(browser);
    try {
      sh('docker stop e2e-bff');

      const publicRead = await request.get(`${BLOG}/api/v1/posts`);
      expect(publicRead.status(), '쿠키가 없으면 BFF 를 거치지 않으므로 공개 읽기는 영향이 없다').toBe(200);

      const withCookie = await ctx.request.get(`${BLOG}/api/v1/bff/session`);
      expect(withCookie.status(), '쿠키가 있는데 BFF 가 없으면 익명으로 넘기지 않고 503').toBe(503);
      expect(((await withCookie.json()) as { code: string }).code).toBe('AUTH-503-02');
      expect(await cookieNamed(ctx, SESSION_COOKIE), '브라우저 쿠키는 그대로').toBeTruthy();
    } finally {
      sh('docker start e2e-bff');
    }
    await waitHealthy(`${state.bff}/actuator/health`);
    sh('docker exec e2e-gateway nginx -s reload');   // 컨테이너 주소가 바뀌었을 수 있어 운영과 같이 게이트웨이를 다시 읽힌다

    expect((await sessionOf(ctx)).authenticated, '복구 뒤 다시 로그인하지 않아도 로그인 상태').toBe(true);
    await ctx.close();
  });
});

async function waitHealthy(url: string, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if ((await fetch(url)).ok) return;
    } catch { /* 아직 기동 중 */ }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error(`${url} 가 다시 올라오지 않았다`);
}
