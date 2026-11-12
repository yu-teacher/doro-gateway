// E2E 스택 준비: 첫 최고 관리자(부트스트랩 API) → OAuth 클라이언트 등록 → 시험용 일반 계정 생성.
// 비밀번호는 실행마다 무작위로 만들어 .state/e2e.json(권한 600)에만 쓴다. 로그에는 이메일만 남긴다.
import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync, chmodSync, readFileSync } from 'node:fs';

const fileEnv = Object.fromEntries(readFileSync(new URL('./e2e.env', import.meta.url), 'utf8')
  .split('\n').filter((l) => l && !l.startsWith('#')).map((l) => l.split('=')));
// 실행할 때 준 E2E_* 환경변수가 파일 값보다 우선한다(예: E2E_PORTAL_PORT)
const env = { ...fileEnv, ...Object.fromEntries(Object.entries(process.env).filter(([k]) => k.startsWith('E2E_'))) };
const MODE = env.E2E_BFF_MODE ?? 'gateway';
const IAM = `http://localhost:${env.E2E_AUTH_PORT}`;
const BLOG_DIRECT = `http://localhost:${env.E2E_BLOG_PORT}`;
// 브라우저가 보는 블로그 주소: 게이트웨이 모드에서는 nginx, embedded 모드에서는 블로그 백엔드 자신
const BLOG = MODE === 'gateway' ? `http://localhost:${env.E2E_GATEWAY_PORT}` : BLOG_DIRECT;

const randomPassword = () => `${randomBytes(12).toString('base64url')}Aa1!`;
const tag = randomBytes(4).toString('hex');

async function api(method, path, { token, body, expect = [200, 201] } = {}) {
  const res = await fetch(IAM + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!expect.includes(res.status)) {
    throw new Error(`${method} ${path} -> HTTP ${res.status} ${text.slice(0, 200)}`);
  }
  return text ? JSON.parse(text) : {};
}

async function signupAndLogin(email, password, name) {
  await api('POST', '/api/v1/auth/signup', { body: { email, password, name } });
  const login = await api('POST', '/api/v1/auth/login', { body: { email, password, deviceInfo: 'e2e-setup' } });
  return login.data.tokens.accessToken;
}

const state = { mode: MODE, iam: IAM, blog: BLOG, blogDirect: BLOG_DIRECT, bff: `http://localhost:${env.E2E_BFF_PORT}`, portal: `http://localhost:${env.E2E_PORTAL_PORT}` };

// 1) 첫 최고 관리자: 가입 → 부트스트랩 토큰으로 승격(모든 세션이 끝나므로 다시 로그인)
state.adminEmail = `e2e-admin-${tag}@doro.local`;
state.adminPassword = randomPassword();
const adminFirst = await signupAndLogin(state.adminEmail, state.adminPassword, 'E2E Admin');
await api('POST', '/api/v1/auth/bootstrap', { token: adminFirst, body: { token: env.E2E_BOOTSTRAP_TOKEN } });
const adminLogin = await api('POST', '/api/v1/auth/login', { body: { email: state.adminEmail, password: state.adminPassword, deviceInfo: 'e2e-setup' } });
const adminToken = adminLogin.data.tokens.accessToken;
console.log(`[setup] 최고 관리자 승격: ${state.adminEmail}`);

// 2) 블로그 OAuth 클라이언트(자사 서비스): redirect_uri 는 이 로컬 블로그의 콜백과 정확히 같아야 한다
await api('POST', '/api/v1/admin/oauth/clients', {
  token: adminToken,
  body: {
    name: 'E2E Blog', clientId: 'doro-blog', firstParty: true,
    redirectUris: [`${BLOG}/api/v1/bff/callback`], scopes: ['openid', 'profile', 'email'],
  },
});
console.log('[setup] OAuth 클라이언트 doro-blog 등록');

// 3) 시험용 일반 계정
state.userEmail = `e2e-user-${tag}@doro.local`;
state.userPassword = randomPassword();
await signupAndLogin(state.userEmail, state.userPassword, 'E2E User');
console.log(`[setup] 시험 계정: ${state.userEmail}`);

mkdirSync(new URL('./.state/', import.meta.url), { recursive: true });
const file = new URL('./.state/e2e.json', import.meta.url);
writeFileSync(file, JSON.stringify(state, null, 2));
chmodSync(file, 0o600);
