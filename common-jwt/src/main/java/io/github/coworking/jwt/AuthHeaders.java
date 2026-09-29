package io.github.coworking.jwt;

/**
 * The headers the gateway attaches to every request it forwards once it has verified the token.
 * <p>
 * Downstream services read the caller's identity from here and never parse the JWT themselves:
 * token verification is the gateway's job, and duplicating it in three services would mean three
 * places to keep in sync with the signing key.
 */
public final class AuthHeaders {

    /** Subject of the verified token: the user's database id. */
    public static final String USER_ID = "X-Auth-User-Id";

    /** Login of the authenticated user, carried for logging and audit. */
    public static final String USER_EMAIL = "X-Auth-User-Email";

    /** Comma-separated roles, e.g. {@code USER,ADMIN}. */
    public static final String USER_ROLES = "X-Auth-Roles";

    private AuthHeaders() {
    }
}
