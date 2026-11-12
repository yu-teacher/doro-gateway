-- BFF 로그인: 브라우저는 세션 쿠키만 가지고, Doro OAuth 토큰은 이 서비스(doro-bff)에만 둔다.
-- 세션 식별자와 state 는 해시로만 저장하고(DB 가 유출돼도 쿠키를 만들 수 없다), 토큰과 PKCE verifier 는 암호화해서 저장한다.
-- service 는 어느 서비스(blog, party, games …)의 세션/로그인 시도인지다.

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    service VARCHAR(32) NOT NULL,
    session_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    access_token_enc TEXT NOT NULL,
    access_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    refresh_token_enc TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 로그인 시점부터의 절대 만료. 리프레시 토큰이 계속 갱신돼도 이 시각이 지나면 다시 로그인해야 한다.
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_auth_sessions_expires_at ON auth_sessions(expires_at);
CREATE INDEX idx_auth_sessions_user_id ON auth_sessions(user_id);

CREATE TABLE login_attempts (
    state_hash VARCHAR(64) PRIMARY KEY,
    service VARCHAR(32) NOT NULL,
    code_verifier_enc TEXT NOT NULL,
    return_path VARCHAR(500) NOT NULL,
    -- 로그인을 시작한 브라우저에 심은 일회용 값의 해시. 콜백이 그 브라우저에서 왔는지 대조한다(로그인 CSRF 방지).
    browser_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_login_attempts_expires_at ON login_attempts(expires_at);

-- Doro 의 탈퇴 목록을 어디까지 처리했는지(주기 조회의 증분 기준)
CREATE TABLE iam_sync_state (
    name VARCHAR(50) PRIMARY KEY,
    cursor_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
