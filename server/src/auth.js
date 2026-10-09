// Solo las cuentas de la organización pueden usar el servicio. La app envía el token de acceso de Google
// que ya tiene para Drive; aquí se comprueba (1) que Google lo emitió para la app esperada y (2) de qué
// cuenta es, preguntándole a Drive. Ese token solo tiene permisos de Drive, así que no trae el correo en
// la consulta de tokeninfo y hay que pedirlo a `about`.

const TOKENINFO = "https://oauth2.googleapis.com/tokeninfo?access_token=";
const DRIVE_ABOUT = "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)";

export function createAuthenticator({ fetchImpl = fetch, config }) {
  const cache = new Map(); // token -> { user, until }

  async function lookup(token) {
    const infoResponse = await fetchImpl(TOKENINFO + encodeURIComponent(token));
    if (!infoResponse.ok) return null;
    const info = await infoResponse.json();
    if (config.allowedAudiences.length > 0 && !config.allowedAudiences.includes(info.aud)) return null;

    let email = String(info.email ?? "").toLowerCase();
    const verified = info.email === undefined || info.email_verified === true || info.email_verified === "true";
    if (!email) {
      const about = await fetchImpl(DRIVE_ABOUT, { headers: { authorization: `Bearer ${token}` } });
      if (!about.ok) return null;
      email = String((await about.json())?.user?.emailAddress ?? "").toLowerCase();
    }
    if (!verified || !config.allowedDomains.some((domain) => email.endsWith(`@${domain}`))) return null;
    return { user: { email }, lifetime: Math.min(Number(info.expires_in) || 0, 300) * 1000 };
  }

  return async function authenticate(request) {
    if (config.authMode === "mock") return { email: "prueba@creanicaragua.org", mock: true };

    const header = request.headers["authorization"] ?? "";
    const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
    if (!token) return null;

    const cached = cache.get(token);
    if (cached && cached.until > Date.now()) return cached.user;

    let found;
    try {
      found = await lookup(token);
    } catch {
      return null;
    }
    if (!found) return null;
    cache.set(token, { user: found.user, until: Date.now() + found.lifetime });
    if (cache.size > 500) cache.delete(cache.keys().next().value);
    return found.user;
  };
}
