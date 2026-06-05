import crypto from "node:crypto";
import type { Context } from "hono";
import { deleteCookie, getCookie, setCookie } from "hono/cookie";
import { createRemoteJWKSet, jwtVerify } from "jose";
import { env } from "../config/env.js";
import { unauthorized } from "../errors/http-error.js";
import type { UserRepository } from "../repositories/user-repository.js";
import { constantTimeEqual } from "./crypto-service.js";

export const REQUEST_USER_ID_HEADER = "X-Chat-User-Id";
const SESSION_COOKIE = "CHATSRIBIDDI_SESSION";
const OAUTH_STATE_COOKIE = "CHATSRIBIDDI_OAUTH_STATE";
const XSRF_COOKIE = "XSRF-TOKEN";

type SessionPayload = {
  sub: string;
  email?: string | null;
  exp: number;
};

type PendingOAuth = {
  state: string;
  verifier: string;
  exp: number;
};

export class AuthService {
  private readonly jwks = createRemoteJWKSet(new URL("https://www.googleapis.com/oauth2/v3/certs"));

  constructor(private readonly userRepository: UserRepository) {}

  resolveUserId(c: Context) {
    const session = this.readSession(c);
    if (session?.sub) return session.sub;
    if (env.isDevProfile) {
      const header = c.req.header(REQUEST_USER_ID_HEADER)?.trim();
      if (header) return header;
    }
    return null;
  }

  requireUserId(c: Context) {
    const userId = this.resolveUserId(c);
    if (!userId) throw unauthorized();
    return userId;
  }

  hasSession(c: Context) {
    return this.readSession(c) !== null;
  }

  async ensureDevUser(userId: string) {
    if (env.isDevProfile) {
      await this.userRepository.findOrCreate(userId, null);
    }
  }

  startGoogleLogin(c: Context) {
    if (!env.GOOGLE_CLIENT_ID || !env.GOOGLE_CLIENT_SECRET) {
      throw new Error("Google OAuth is not configured");
    }
    const state = randomUrlToken(32);
    const verifier = randomUrlToken(64);
    const challenge = base64Url(crypto.createHash("sha256").update(verifier).digest());
    const redirectUri = this.googleRedirectUri();
    const params = new URLSearchParams({
      client_id: env.GOOGLE_CLIENT_ID,
      redirect_uri: redirectUri,
      response_type: "code",
      scope: "openid email profile",
      state,
      code_challenge: challenge,
      code_challenge_method: "S256",
      prompt: "select_account",
    });
    setSignedCookie(c, OAUTH_STATE_COOKIE, { state, verifier, exp: Date.now() + 10 * 60_000 }, 600);
    return c.redirect(`https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`, 302);
  }

  async completeGoogleLogin(c: Context) {
    if (!env.GOOGLE_CLIENT_ID || !env.GOOGLE_CLIENT_SECRET) {
      return c.redirect(`${env.APP_FRONTEND_URL}/login?error=oauth_not_configured`, 302);
    }
    const pending = readSignedCookie<PendingOAuth>(c, OAUTH_STATE_COOKIE);
    deleteCookie(c, OAUTH_STATE_COOKIE, cookieOptions(false));
    const state = c.req.query("state");
    const code = c.req.query("code");
    const error = c.req.query("error");
    if (error)
      return c.redirect(`${env.APP_FRONTEND_URL}/login?error=${encodeURIComponent(error)}`, 302);
    if (
      !pending ||
      pending.exp < Date.now() ||
      !state ||
      !constantTimeEqual(pending.state, state)
    ) {
      return c.redirect(`${env.APP_FRONTEND_URL}/login?error=invalid_state`, 302);
    }
    if (!code) {
      return c.redirect(`${env.APP_FRONTEND_URL}/login?error=missing_code`, 302);
    }

    const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        code,
        client_id: env.GOOGLE_CLIENT_ID ?? "",
        client_secret: env.GOOGLE_CLIENT_SECRET ?? "",
        redirect_uri: this.googleRedirectUri(),
        grant_type: "authorization_code",
        code_verifier: pending.verifier,
      }),
    });
    if (!tokenResponse.ok) {
      const body = await tokenResponse.text();
      console.warn("Google OAuth token exchange failed", tokenResponse.status, body);
      return c.redirect(`${env.APP_FRONTEND_URL}/login?error=token_exchange_failed`, 302);
    }
    const tokens = (await tokenResponse.json()) as { id_token?: string };
    if (!tokens.id_token) {
      return c.redirect(`${env.APP_FRONTEND_URL}/login?error=missing_id_token`, 302);
    }
    const { payload } = await jwtVerify(tokens.id_token, this.jwks, {
      issuer: ["https://accounts.google.com", "accounts.google.com"],
      audience: env.GOOGLE_CLIENT_ID,
    });
    const sub = payload.sub;
    if (!sub) {
      return c.redirect(`${env.APP_FRONTEND_URL}/login?error=missing_subject`, 302);
    }
    const email = typeof payload.email === "string" ? payload.email : null;
    await this.userRepository.findOrCreate(sub, email);
    this.writeSession(c, { sub, email, exp: Date.now() + env.sessionMaxAgeMs });
    ensureXsrfCookie(c);
    return c.redirect(env.APP_FRONTEND_URL, 302);
  }

  logout(c: Context) {
    deleteCookie(c, SESSION_COOKIE, cookieOptions(true));
    deleteCookie(c, XSRF_COOKIE, cookieOptions(false));
    return c.body(null, 204);
  }

  validateCsrf(c: Context) {
    if (!["POST", "PUT", "PATCH", "DELETE"].includes(c.req.method)) return;
    if (c.req.path === "/anonymous/chats/stream") return;
    if (!this.readSession(c)) return;
    const cookie = getCookie(c, XSRF_COOKIE);
    const header = c.req.header("X-XSRF-TOKEN");
    if (!cookie || !header || !constantTimeEqual(cookie, header)) {
      throw unauthorized("CSRF token is missing or invalid");
    }
  }

  ensureXsrf(c: Context) {
    ensureXsrfCookie(c);
  }

  private readSession(c: Context) {
    const session = readSignedCookie<SessionPayload>(c, SESSION_COOKIE);
    if (!session || session.exp < Date.now()) return null;
    return session;
  }

  private writeSession(c: Context, payload: SessionPayload) {
    setSignedCookie(c, SESSION_COOKIE, payload, Math.floor(env.sessionMaxAgeMs / 1000), true);
  }

  private googleRedirectUri() {
    return googleLoginRedirectUri();
  }
}

export function googleLoginRedirectUri(publicOrigin = env.APP_FRONTEND_URL) {
  return new URL("/login/oauth2/code/google", publicOrigin).toString();
}

function randomUrlToken(bytes: number) {
  return base64Url(crypto.randomBytes(bytes));
}

function base64Url(buffer: Buffer) {
  return buffer.toString("base64").replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function sign(payload: string) {
  return crypto.createHmac("sha256", env.sessionSecret).update(payload).digest("base64url");
}

function setSignedCookie(
  c: Context,
  name: string,
  payload: unknown,
  maxAgeSeconds: number,
  httpOnly = true,
) {
  const data = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
  setCookie(c, name, `${data}.${sign(data)}`, {
    ...cookieOptions(httpOnly),
    maxAge: maxAgeSeconds,
  });
}

function readSignedCookie<T>(c: Context, name: string): T | null {
  const raw = getCookie(c, name);
  if (!raw) return null;
  const [data, signature] = raw.split(".", 2);
  if (!data || !signature || !constantTimeEqual(signature, sign(data))) return null;
  try {
    return JSON.parse(Buffer.from(data, "base64url").toString("utf8")) as T;
  } catch {
    return null;
  }
}

function cookieOptions(httpOnly: boolean) {
  return {
    path: "/",
    httpOnly,
    secure: env.isProduction,
    sameSite: "Lax" as const,
  };
}

function ensureXsrfCookie(c: Context) {
  if (getCookie(c, XSRF_COOKIE)) return;
  setCookie(c, XSRF_COOKIE, randomUrlToken(24), {
    ...cookieOptions(false),
    maxAge: Math.floor(env.sessionMaxAgeMs / 1000),
  });
}
