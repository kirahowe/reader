// Cloudflare Email Worker for Reader inbound newsletters.
//
// Bound as the Email Routing catch-all for the inbound domain. For each message
// it: stores the raw .eml in R2 (native binding, no creds), then signs and POSTs
// a small notification to the app's /api/inbound. The app verifies the HMAC,
// resolves the recipient alias to a user, and enqueues an :ingest-email job that
// reads the .eml back from R2 and files it.
//
// Deliberately dumb: no MIME parsing here — that's done once, server-side, in
// Clojure. The signing contract MUST match reader.web.signature:
//   signature = hex(HMAC_SHA256(secret, `${timestamp}.${body}`))
// sent as the x-reader-timestamp / x-reader-signature headers, where body is the
// exact JSON string POSTed.
//
// Bindings / vars (see wrangler.toml):
//   INBOUND_BUCKET        R2 bucket binding
//   READER_API_URL        app origin, e.g. https://kirahowe-reader.fly.dev
//   INBOUND_HMAC_SECRET   secret (wrangler secret put INBOUND_HMAC_SECRET)

export default {
  async email(message, env) {
    const bytes = new Uint8Array(await new Response(message.raw).arrayBuffer());

    const key = `inbox/${crypto.randomUUID()}.eml`;
    await env.INBOUND_BUCKET.put(key, bytes, {
      httpMetadata: { contentType: "message/rfc822" },
    });

    const payload = {
      "alias": message.to,
      "r2-key": key,
      // Message-ID is the idempotency key server-side; fall back to a unique
      // value so a (rare) header-less email still passes validation.
      "message-id": message.headers.get("message-id") || `gen-${key}`,
      "from": message.from,
      "subject": message.headers.get("subject") || "",
      "size": bytes.length,
    };
    const body = JSON.stringify(payload);
    const ts = Math.floor(Date.now() / 1000).toString();
    const signature = await hmacHex(env.INBOUND_HMAC_SECRET, `${ts}.${body}`);

    const resp = await fetch(`${env.READER_API_URL}/api/inbound`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-reader-timestamp": ts,
        "x-reader-signature": signature,
      },
      body,
    });

    // 404 = the alias isn't one of ours; drop quietly rather than retry forever.
    // Any other non-2xx is transient from our side — throw so Email Routing retries.
    if (!resp.ok && resp.status !== 404) {
      throw new Error(`inbound POST failed: ${resp.status}`);
    }
  },
};

async function hmacHex(secret, message) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(message));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
