package de.jost_net.JVerein.hikvision;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.logging.Logger;

/**
 * Hikvision DS-K2702WX ISAPI client.
 *
 * Uses {@link java.net.http.HttpClient} (Java 11+) instead of
 * {@code HttpURLConnection} because Jameica installs a default
 * {@link java.net.Authenticator} (for proxy / SSO concerns) which
 * consumes the WWW-Authenticate header on a 401 before our code can
 * read it — causing "no WWW-Authenticate header" errors on the first
 * request to a digest-protected endpoint. {@code HttpClient} doesn't
 * consult the default Authenticator, so the challenge stays visible.
 *
 * Hikvision controller quirk: the digest nonce is single-use; every
 * request does a fresh 401 → challenge → re-send dance. Sharing or
 * caching the nonce will get the 2nd call rejected.
 *
 * The self-signed controller cert is trusted unconditionally — this is
 * a LAN device behind digest auth.
 */
public class HikvisionClient
{
  private final String baseUrl;
  private final String user;
  private final String password;
  private final int pauseMs;
  private final HttpClient http;

  public HikvisionClient(String baseUrl, String user, String password, int pauseMs)
  {
    this(baseUrl, user, password, pauseMs, false);
  }

  public HikvisionClient(String baseUrl, String user, String password, int pauseMs, boolean verifySsl)
  {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.user = user;
    this.password = password;
    this.pauseMs = pauseMs;
    HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
    if (!verifySsl)
    {
      // Trust-all bypasses cert chain validation, but the JDK still does
      // hostname/IP verification against the cert's SAN as a separate step
      // (controlled via SSLParameters.endpointIdentificationAlgorithm —
      // defaults to "HTTPS" for HttpClient). Hikvision DS-K's self-signed
      // cert has no SAN entry for whatever local IP/host the controller
      // got from DHCP, so this fails with "No subject alternative names
      // matching IP address …" unless we explicitly disable EIA too.
      SSLContext ctx = trustAllSslContext();
      b.sslContext(ctx);
      SSLParameters sp = ctx.getDefaultSSLParameters();
      sp.setEndpointIdentificationAlgorithm("");   // "" disables hostname check
      b.sslParameters(sp);
    }
    this.http = b.build();
  }

  // ---------------------------------------------------------- Cancellation

  /** Optional cancellation hook. The sync / refresh code wires this to the
   *  Jameica background task's interrupt flag so a wedged controller call
   *  can be abandoned promptly. Without it, a single hung {@code send()}
   *  blocks Jameica's one-at-a-time background-task slot indefinitely —
   *  observed in the field: a UserInfo/Search call stopped logging at
   *  pos=300 and never returned, after which neither the cancel button nor
   *  any later sync / refresh could run ("there's already running a
   *  background task"). Default never-cancelled so callers that don't set
   *  it keep working. */
  private volatile BooleanSupplier cancelCheck = () -> false;

  public void setCancelCheck(BooleanSupplier c) { this.cancelCheck = (c == null) ? () -> false : c; }

  private boolean cancelled()
  {
    try { return cancelCheck.getAsBoolean(); }
    catch (Exception e) { return false; }
  }

  // ---------------------------------------------------------------- HTTP

  /** Default max attempts per request. The controller intermittently rejects
   *  a valid digest with a 401 {@code <userCheck>} (single-use nonce /
   *  session-pool contention under sustained load) or drops the connection.
   *  A single such hiccup must NOT abort a ~60-call full refresh, so we re-do
   *  the full challenge-response with a fresh nonce a few times before giving
   *  up. Overridable via {@link #setResilience} (controller settings). */
  private static final int DEFAULT_MAX_ATTEMPTS = 4;

  /** Default absolute ceiling (ms) for a single round-trip, enforced by our
   *  own polling loop in {@link #send} independently of the JDK HttpClient
   *  request timeout — which has been observed NOT to fire on this controller
   *  (a call once wedged for hours). A little above the per-request
   *  {@link #send} timeout (20s) so the JDK timeout normally wins and we only
   *  step in when it misbehaves; on expiry we throw {@link HttpTimeoutException}
   *  so the normal (limited) retry/backoff path runs. Overridable via
   *  {@link #setResilience}. */
  private static final long DEFAULT_CALL_DEADLINE_MS = 30_000;

  private volatile int  maxAttempts    = DEFAULT_MAX_ATTEMPTS;
  private volatile long callDeadlineMs = DEFAULT_CALL_DEADLINE_MS;

  /** Override the resilience knobs from settings. {@code maxAttempts} is
   *  clamped to ≥1 (1 = try once, no retry); {@code callDeadlineMs} to ≥1000. */
  public void setResilience(int maxAttempts, long callDeadlineMs)
  {
    this.maxAttempts    = Math.max(1, maxAttempts);
    this.callDeadlineMs = Math.max(1000L, callDeadlineMs);
  }

  /** One logical round-trip with fresh digest, retried on transient 401 /
   *  connection failures. Throws on a genuine non-2xx (other than a retried
   *  401) or after all attempts are exhausted. */
  public String request(String method, String path, String body) throws IOException
  {
    String url = baseUrl + path;
    IOException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++)
    {
      HttpResponse<String> r1;
      try { r1 = send(method, url, body, null); }
      catch (InterruptedIOException ie) { throw ie; }   // cancellation — never retry
      catch (IOException e) { last = e; if (!backoff(attempt, method, path, e)) throw e; continue; }

      if (r1.statusCode() != 401)
      {
        if (r1.statusCode() >= 200 && r1.statusCode() < 300) return r1.body();
        // non-401 error without an auth challenge → fatal (not retryable)
        throw new IOException("HTTP " + r1.statusCode() + " on " + method + " " + path + ": " + r1.body());
      }

      String challenge = r1.headers().firstValue("WWW-Authenticate").orElse(null);
      if (challenge == null) throw new IOException("no WWW-Authenticate header from " + url);

      String auth = buildDigestHeader(challenge, method, path);
      HttpResponse<String> r2;
      try { r2 = send(method, url, body, auth); }
      catch (InterruptedIOException ie) { throw ie; }
      catch (IOException e) { last = e; if (!backoff(attempt, method, path, e)) throw e; continue; }

      int code = r2.statusCode();
      if (code >= 200 && code < 300) return r2.body();
      if (code == 401)
      {
        // Auth rejected though credentials are valid → transient; the request
        // was NOT processed, so retrying with a fresh nonce is safe.
        last = new IOException("HTTP 401 on " + method + " " + path + ": " + r2.body());
        if (!backoff(attempt, method, path, last)) throw last;
        continue;
      }
      throw new IOException("HTTP " + code + " on " + method + " " + path + ": " + r2.body());
    }
    throw last != null ? last
        : new IOException("Hikvision-Request fehlgeschlagen nach " + maxAttempts + " Versuchen: " + method + " " + path);
  }

  /** Log + pause before the next attempt. Returns false when no attempts are
   *  left (caller should then throw). Pauses one inter-call interval to let
   *  the controller's session pool recover. */
  private boolean backoff(int attempt, String method, String path, IOException cause) throws InterruptedIOException
  {
    if (attempt >= maxAttempts) return false;
    Logger.warn("Hikvision-Wiederholung " + (attempt + 1) + "/" + maxAttempts + " für " + method + " " + path
        + " nach: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    sleepCancelable(Math.max(pauseMs, 1500));
    return true;
  }

  /** Cancel-aware sleep: wakes in short slices and throws on cancel, so an
   *  inter-call pause or retry backoff never delays an abort. */
  private void sleepCancelable(long ms) throws InterruptedIOException
  {
    long endNanos = System.nanoTime() + ms * 1_000_000L;
    while (true)
    {
      if (cancelled()) throw new InterruptedIOException("Abgebrochen");
      long remNanos = endNanos - System.nanoTime();
      if (remNanos <= 0) return;
      try { Thread.sleep(Math.min(200L, remNanos / 1_000_000L + 1)); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(e.getMessage()); }
    }
  }

  private HttpResponse<String> send(String method, String url, String body, String authHeader) throws IOException
  {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(20));
    HttpRequest.BodyPublisher bp = body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
    if (body != null) b.header("Content-Type", "application/json");
    if (authHeader != null) b.header("Authorization", authHeader);
    switch (method)
    {
      case "GET":    b.GET();        break;
      case "POST":   b.POST(bp);     break;
      case "PUT":    b.PUT(bp);      break;
      case "DELETE": b.DELETE();     break;
      default:       b.method(method, bp);
    }
    // Async send + bounded polling instead of the synchronous http.send():
    //  - we ABANDON the wait on cancel or on our own hard deadline (throw),
    //    without needing the wedged exchange to actually unblock — the
    //    orphaned future is cancelled and left to expire on its own;
    //  - cancellation throws InterruptedIOException (never retried);
    //  - the hard deadline throws HttpTimeoutException so request()'s
    //    limited retry/backoff path handles it like any other timeout.
    // This is what stops a single hung call from holding Jameica's
    // one-at-a-time background-task slot forever.
    CompletableFuture<HttpResponse<String>> f =
        http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    long deadlineNanos = System.nanoTime() + callDeadlineMs * 1_000_000L;
    try
    {
      while (true)
      {
        if (cancelled()) { f.cancel(true); throw new InterruptedIOException("Abgebrochen"); }
        try { return f.get(250, TimeUnit.MILLISECONDS); }
        catch (TimeoutException te)
        {
          if (System.nanoTime() >= deadlineNanos)
          {
            f.cancel(true);
            throw new HttpTimeoutException("Controller-Aufruf überschritt " + callDeadlineMs
                + "ms: " + method + " " + url);
          }
        }
      }
    }
    catch (InterruptedException ie)
    { f.cancel(true); Thread.currentThread().interrupt(); throw new InterruptedIOException(ie.getMessage()); }
    catch (ExecutionException ee)
    {
      f.cancel(true);
      Throwable c = ee.getCause();
      if (c instanceof InterruptedIOException) throw (InterruptedIOException) c;
      if (c instanceof IOException)             throw (IOException) c;
      throw new IOException(c == null ? ee.toString() : c.toString(), c);
    }
  }

  // ---------------------------------------------------------- Digest auth

  private static final Pattern KV = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");
  private static final SecureRandom RAND = new SecureRandom();

  private String buildDigestHeader(String challenge, String method, String path)
  {
    if (!challenge.toLowerCase().startsWith("digest "))
      throw new IllegalArgumentException("not a Digest challenge: " + challenge);
    Map<String, String> p = new HashMap<>();
    Matcher m = KV.matcher(challenge.substring("digest ".length()));
    while (m.find()) p.put(m.group(1).toLowerCase(), m.group(2) != null ? m.group(2) : m.group(3));

    String realm = p.get("realm");
    String nonce = p.get("nonce");
    String qop = p.get("qop");
    String algorithm = p.getOrDefault("algorithm", "MD5");
    String opaque = p.get("opaque");

    String cnonce = randomHex(16);
    String nc = "00000001";
    String ha1 = md5Hex(user + ":" + realm + ":" + password);
    String ha2 = md5Hex(method + ":" + path);
    String response = qop != null && (qop.equals("auth") || qop.contains("auth"))
        ? md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
        : md5Hex(ha1 + ":" + nonce + ":" + ha2);

    StringBuilder h = new StringBuilder("Digest ");
    h.append("username=\"").append(user).append("\", ");
    h.append("realm=\"").append(realm).append("\", ");
    h.append("nonce=\"").append(nonce).append("\", ");
    h.append("uri=\"").append(path).append("\", ");
    h.append("algorithm=").append(algorithm).append(", ");
    if (qop != null) {
      h.append("qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\", ");
    }
    h.append("response=\"").append(response).append("\"");
    if (opaque != null) h.append(", opaque=\"").append(opaque).append("\"");
    return h.toString();
  }

  private static String md5Hex(String s)
  {
    try
    {
      byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(32);
      for (byte b : d) sb.append(String.format("%02x", b));
      return sb.toString();
    }
    catch (Exception e) { throw new RuntimeException(e); }
  }

  private static String randomHex(int bytes)
  {
    byte[] b = new byte[bytes];
    RAND.nextBytes(b);
    StringBuilder sb = new StringBuilder(bytes * 2);
    for (byte x : b) sb.append(String.format("%02x", x));
    return sb.toString();
  }

  // ----------------------------------------------------- ISAPI operations

  private void pace() throws InterruptedIOException { sleepCancelable(pauseMs); }

  private JSONObject postJson(String path, JSONObject body) throws IOException
  {
    return new JSONObject(request("POST", path + "?format=json", body.toString()));
  }

  private JSONObject putJson(String path, JSONObject body) throws IOException
  {
    return new JSONObject(request("PUT", path + "?format=json", body.toString()));
  }

  /** All users on the controller — paged. Optional listener gets per-batch progress. */
  public JSONArray listAllUsers() throws IOException { return listAllUsers(null); }

  public JSONArray listAllUsers(ProgressListener pl) throws IOException
  {
    JSONArray out = new JSONArray();
    int pos = 0;
    while (true)
    {
      JSONObject body = new JSONObject().put("UserInfoSearchCond",
          new JSONObject().put("searchID", "j-" + pos)
                          .put("searchResultPosition", pos)
                          .put("maxResults", 200));
      JSONObject res = postJson("/ISAPI/AccessControl/UserInfo/Search", body);
      JSONObject inner = res.getJSONObject("UserInfoSearch");
      int total = inner.optInt("totalMatches", 0);
      JSONArray items = inner.optJSONArray("UserInfo");
      if (items != null) for (int i = 0; i < items.length(); i++) out.put(items.getJSONObject(i));
      int got = items != null ? items.length() : 0;
      Logger.info("Hikvision UserInfo/Search pos=" + pos + " got=" + got + " total=" + total);
      if (pl != null) pl.progress(out.length(), total, "Benutzer abrufen");
      if (got == 0 || out.length() >= total) break;
      if (pl != null && pl.isCancelled())
        throw new InterruptedIOException("Abgebrochen nach " + out.length() + "/" + total + " Benutzern");
      pos += got;
      pace();
    }
    return out;
  }

  /** All cards on the controller — paged. Optional listener gets per-batch progress. */
  public JSONArray listAllCards() throws IOException { return listAllCards(null); }

  public JSONArray listAllCards(ProgressListener pl) throws IOException
  {
    JSONArray out = new JSONArray();
    int pos = 0;
    while (true)
    {
      JSONObject body = new JSONObject().put("CardInfoSearchCond",
          new JSONObject().put("searchID", "j-" + pos)
                          .put("searchResultPosition", pos)
                          .put("maxResults", 200));
      JSONObject res = postJson("/ISAPI/AccessControl/CardInfo/Search", body);
      JSONObject inner = res.getJSONObject("CardInfoSearch");
      int total = inner.optInt("totalMatches", 0);
      JSONArray items = inner.optJSONArray("CardInfo");
      if (items != null) for (int i = 0; i < items.length(); i++) out.put(items.getJSONObject(i));
      int got = items != null ? items.length() : 0;
      Logger.info("Hikvision CardInfo/Search pos=" + pos + " got=" + got + " total=" + total);
      if (pl != null) pl.progress(out.length(), total, "Karten abrufen");
      if (got == 0 || out.length() >= total) break;
      if (pl != null && pl.isCancelled())
        throw new InterruptedIOException("Abgebrochen nach " + out.length() + "/" + total + " Karten");
      pos += got;
      pace();
    }
    return out;
  }

  /**
   * Enumerate the organisational user groups via
   * {@code UserGroupMgr/SearchUserGroup}. Every user must belong to exactly
   * one of these ({@code userGroupNodeID}); they are the org tree
   * (BSV / Vorstand / Mitglieder / Robby Bubble) — including groups that
   * currently have no members, which the old "derive from existing UserInfo"
   * approach could never surface.
   *
   * <p>NB: belonging to a userGroup is <i>not</i> the same as having door
   * access — that is governed by the region-permission groups
   * ({@link #listRegionPermissionGroups}).
   *
   * <p>Quirks on DS-K2702WX (firmware V1.7.4): pagination is <b>1-based</b>
   * (a {@code searchResultPosition} of 0 is rejected) and {@code maxResults}
   * caps at 33 — we request 30. Each match has {@code nodeID} (= the
   * {@code userGroupNodeID} on UserInfo records), {@code nodeName},
   * {@code nodeLevel}, {@code parentNodeID} and {@code userNum}.
   */
  public JSONArray listUserGroups() throws IOException
  {
    JSONArray out = new JSONArray();
    int pos = 1;   // this endpoint is 1-based (0 → badJsonContent)
    while (true)
    {
      JSONObject body = new JSONObject()
          .put("searchID", "jg-" + pos)
          .put("searchResultPosition", pos)
          .put("maxResults", 30);
      JSONObject res = postJson("/ISAPI/AccessControl/UserGroupMgr/SearchUserGroup", body);
      int total = res.optInt("totalMatches", 0);
      JSONArray items = res.optJSONArray("matchResults");
      int got = items != null ? items.length() : 0;
      if (items != null) for (int i = 0; i < items.length(); i++) out.put(items.getJSONObject(i));
      Logger.info("Hikvision SearchUserGroup pos=" + pos + " got=" + got + " total=" + total);
      if (got == 0 || out.length() >= total) break;
      pos += got;
      pace();
    }
    return out;
  }

  /**
   * Enumerate the region-permission groups (Berechtigungsgruppen / door
   * access) via {@code DoorRegionMgr/SearchRegionPermissionGroup}. THIS is
   * what actually grants door access: a user is granted access by having a
   * group's id in their {@code regionPermissionGroupIDList}. Members can
   * hold several at once.
   *
   * <p>Same 1-based pagination quirk as {@link #listUserGroups}. Each match
   * carries {@code regionPermissionGroupID} (int), {@code regionPermissionGroupName},
   * a {@code doorIDList} (door id + region node name), {@code userNum} and
   * {@code userGroupNum}.
   */
  public JSONArray listRegionPermissionGroups() throws IOException
  {
    JSONArray out = new JSONArray();
    int pos = 1;   // 1-based, same as SearchUserGroup
    while (true)
    {
      JSONObject body = new JSONObject()
          .put("searchID", "rp-" + pos)
          .put("searchResultPosition", pos)
          .put("maxResults", 30);
      JSONObject res = postJson("/ISAPI/AccessControl/DoorRegionMgr/SearchRegionPermissionGroup", body);
      int total = res.optInt("totalMatches", 0);
      JSONArray items = res.optJSONArray("matchResults");
      int got = items != null ? items.length() : 0;
      if (items != null) for (int i = 0; i < items.length(); i++) out.put(items.getJSONObject(i));
      Logger.info("Hikvision SearchRegionPermissionGroup pos=" + pos + " got=" + got + " total=" + total);
      if (got == 0 || out.length() >= total) break;
      pos += got;
      pace();
    }
    return out;
  }

  /** Max employeeNos per scoped Search request. DS-K2702WX honors larger
   *  batches, but we cap to keep one POST predictable and to stay well
   *  under any controller-side request-size limits. */
  private static final int SCOPED_BATCH = 100;

  /** Scoped variant of {@link #listAllUsers}: fetches only the named
   *  employeeNos via the {@code EmployeeNoList} filter on
   *  UserInfoSearchCond. Missing employeeNos are silently dropped by the
   *  controller (verified on DS-K2702WX). Batched in groups of
   *  {@value #SCOPED_BATCH}. */
  public JSONArray listUsers(Collection<String> employeeNos, ProgressListener pl) throws IOException
  {
    JSONArray out = new JSONArray();
    if (employeeNos == null || employeeNos.isEmpty()) return out;
    List<List<String>> batches = chunk(new ArrayList<>(employeeNos), SCOPED_BATCH);
    int done = 0, total = employeeNos.size();
    for (List<String> batch : batches)
    {
      JSONArray empList = new JSONArray();
      for (String e : batch) empList.put(new JSONObject().put("employeeNo", e));
      JSONObject body = new JSONObject().put("UserInfoSearchCond",
          new JSONObject().put("searchID", "j-scoped-" + done)
                          .put("searchResultPosition", 0)
                          .put("maxResults", batch.size())
                          .put("EmployeeNoList", empList));
      JSONObject res = postJson("/ISAPI/AccessControl/UserInfo/Search", body);
      JSONObject inner = res.getJSONObject("UserInfoSearch");
      JSONArray items = inner.optJSONArray("UserInfo");
      int got = items != null ? items.length() : 0;
      if (items != null) for (int i = 0; i < items.length(); i++) out.put(items.getJSONObject(i));
      Logger.info("Hikvision UserInfo/Search scoped batch=" + batch.size() + " got=" + got);
      done += batch.size();
      if (pl != null) pl.progress(done, total, "Benutzer abrufen (gezielt)");
      if (pl != null && pl.isCancelled())
        throw new InterruptedIOException("Abgebrochen nach " + done + "/" + total + " Benutzern");
      if (batches.size() > 1) pace();
    }
    return out;
  }

  /** Scoped variant of {@link #listAllCards}: fetches only cards belonging
   *  to the named employeeNos via the {@code EmployeeNoList} filter on
   *  CardInfoSearchCond. Batched in groups of {@value #SCOPED_BATCH}. */
  public JSONArray listCards(Collection<String> employeeNos, ProgressListener pl) throws IOException
  {
    JSONArray out = new JSONArray();
    if (employeeNos == null || employeeNos.isEmpty()) return out;
    List<List<String>> batches = chunk(new ArrayList<>(employeeNos), SCOPED_BATCH);
    int done = 0, total = employeeNos.size();
    for (List<String> batch : batches)
    {
      JSONArray empList = new JSONArray();
      for (String e : batch) empList.put(new JSONObject().put("employeeNo", e));
      // One employeeNo can own multiple cards; pad maxResults so a card-rich
      // batch (≤5 cards/person seen in practice) still completes in one POST.
      JSONObject body = new JSONObject().put("CardInfoSearchCond",
          new JSONObject().put("searchID", "j-scoped-" + done)
                          .put("searchResultPosition", 0)
                          .put("maxResults", Math.max(batch.size() * 5, 200))
                          .put("EmployeeNoList", empList));
      JSONObject res = postJson("/ISAPI/AccessControl/CardInfo/Search", body);
      JSONObject inner = res.getJSONObject("CardInfoSearch");
      JSONArray items = inner.optJSONArray("CardInfo");
      int got = items != null ? items.length() : 0;
      if (items != null) for (int i = 0; i < items.length(); i++) out.put(items.getJSONObject(i));
      Logger.info("Hikvision CardInfo/Search scoped batch=" + batch.size() + " got=" + got);
      done += batch.size();
      if (pl != null) pl.progress(done, total, "Karten abrufen (gezielt)");
      if (pl != null && pl.isCancelled())
        throw new InterruptedIOException("Abgebrochen nach " + done + "/" + total + " Karten-Lookup");
      if (batches.size() > 1) pace();
    }
    return out;
  }

  /** Find the employeeNo a card is currently assigned to, or null if the card
   *  isn't on the controller. Used by the sync to recover from a transponder
   *  move whose donor wasn't in the plan: the optimistic createCard then fails
   *  because the card is still attached elsewhere, and we need the donor to
   *  free the card and to fix the cache. Filters CardInfo/Search by cardNo. */
  public String findCardOwner(String cardNo) throws IOException
  {
    if (cardNo == null || cardNo.isEmpty()) return null;
    JSONObject body = new JSONObject().put("CardInfoSearchCond",
        new JSONObject().put("searchID", "j-owner")
                        .put("searchResultPosition", 0)
                        .put("maxResults", 1)
                        .put("CardNoList", new JSONArray().put(new JSONObject().put("cardNo", cardNo))));
    JSONObject res = postJson("/ISAPI/AccessControl/CardInfo/Search", body);
    JSONObject inner = res.optJSONObject("CardInfoSearch");
    if (inner == null) return null;
    JSONArray items = inner.optJSONArray("CardInfo");
    if (items == null || items.length() == 0) return null;
    JSONObject card0 = items.getJSONObject(0);
    // Guard a controller that ignores the CardNoList filter and returns an
    // arbitrary first card — only trust an exact cardNo match.
    if (!cardNo.equals(card0.optString("cardNo"))) return null;
    String emp = card0.optString("employeeNo", "");
    return emp.isEmpty() ? null : emp;
  }

  /** Cheap O(1) probe: returns the controller's totalMatches for UserInfo
   *  without pulling any records. Used to detect drift before deciding
   *  between incremental and full refresh. */
  public int getTotalUsers() throws IOException
  {
    JSONObject body = new JSONObject().put("UserInfoSearchCond",
        new JSONObject().put("searchID", "j-count")
                        .put("searchResultPosition", 0)
                        .put("maxResults", 1));
    JSONObject res = postJson("/ISAPI/AccessControl/UserInfo/Search", body);
    return res.getJSONObject("UserInfoSearch").optInt("totalMatches", -1);
  }

  /** Cheap O(1) probe: see {@link #getTotalUsers}. */
  public int getTotalCards() throws IOException
  {
    JSONObject body = new JSONObject().put("CardInfoSearchCond",
        new JSONObject().put("searchID", "j-count")
                        .put("searchResultPosition", 0)
                        .put("maxResults", 1));
    JSONObject res = postJson("/ISAPI/AccessControl/CardInfo/Search", body);
    return res.getJSONObject("CardInfoSearch").optInt("totalMatches", -1);
  }

  private static <T> List<List<T>> chunk(List<T> src, int size)
  {
    List<List<T>> out = new ArrayList<>();
    for (int i = 0; i < src.size(); i += size)
      out.add(src.subList(i, Math.min(i + size, src.size())));
    return out;
  }

  /**
   * Create a new user record. Defaults to enabled (the previous version
   * had {@code enable:false} hard-coded, which left every newly synced
   * member disabled on the controller until manually fixed).
   *
   * {@code beginTime} / {@code endTime} drive the controller's
   * auto-expiry — pass jverein's eintritt / austritt to let Hikvision
   * disable the user automatically on the configured date even without
   * a sync. Pass null/empty to use the wide-open defaults
   * (2000-01-01 / 2037-12-31).
   *
   * {@code userGroupNodeID}/{@code userGroupNodeName} place the user in the
   * organisational group (required). {@code regionPermissionGroupIds} grants
   * door access — pass the ids of the region-permission groups the user
   * should belong to (may be empty/null for none).
   */
  public boolean createUser(String employeeNo, String name, String userType,
                            String groupId, String groupName,
                            boolean enable, String beginTime, String endTime,
                            String gender, java.util.List<Integer> regionPermissionGroupIds) throws IOException
  {
    JSONObject u = new JSONObject();
    u.put("employeeNo", employeeNo);
    u.put("name", name);
    u.put("userType", userType);
    u.put("closeDelayEnabled", false);
    u.put("Valid", new JSONObject()
        .put("enable", enable)
        .put("beginTime", beginTime == null || beginTime.isEmpty() ? "2000-01-01T00:00:00" : beginTime)
        .put("endTime",   endTime   == null || endTime.isEmpty()   ? "2037-12-31T23:59:59" : endTime)
        .put("timeType", "local"));
    u.put("belongGroup", "");
    u.put("password", "");
    u.put("localPassword", "");
    u.put("userGroupNodeID", groupId);
    u.put("userGroupNodeName", groupName);
    if (regionPermissionGroupIds != null && !regionPermissionGroupIds.isEmpty())
      u.put("regionPermissionGroupIDList", new JSONArray(regionPermissionGroupIds));
    u.put("doorRight", "");
    u.put("maxOpenDoorTime", 0);
    u.put("openDoorTime", 0);
    if (gender != null && !gender.isEmpty()) u.put("gender", gender);
    JSONObject res = postJson("/ISAPI/AccessControl/UserInfo/Record",
        new JSONObject().put("UserInfo", u));
    pace();
    return isOk(res);
  }

  /** Replace the user's region-permission-group membership (door access).
   *  Sends the full desired id list — the controller treats this as the
   *  new complete set, so it both adds and removes. An empty list clears
   *  all individually-assigned region permissions. */
  public boolean setUserRegionPermissionGroups(String employeeNo, java.util.List<Integer> regionPermissionGroupIds) throws IOException
  {
    JSONArray ids = new JSONArray(regionPermissionGroupIds == null
        ? java.util.Collections.emptyList() : regionPermissionGroupIds);
    return modifyUser(employeeNo, new JSONObject().put("regionPermissionGroupIDList", ids));
  }

  /**
   * Partial-modify a user. Hikvision's Modify endpoint accepts a UserInfo
   * with only the fields you want to change (plus employeeNo). Untouched
   * fields (including userType — important for blackList) are preserved.
   */
  public boolean modifyUser(String employeeNo, JSONObject changes) throws IOException
  {
    JSONObject u = new JSONObject(changes.toString());   // shallow copy
    u.put("employeeNo", employeeNo);
    JSONObject res = putJson("/ISAPI/AccessControl/UserInfo/Modify",
        new JSONObject().put("UserInfo", u));
    pace();
    return isOk(res);
  }

  /** Flip Valid.enable and optionally rewrite Valid.endTime in one PUT. */
  public boolean setUserValid(String employeeNo, boolean enable, String endTime) throws IOException
  {
    JSONObject valid = new JSONObject()
        .put("enable", enable)
        .put("beginTime", "2000-01-01T00:00:00")
        .put("endTime", endTime == null || endTime.isEmpty() ? "2037-12-31T23:59:59" : endTime)
        .put("timeType", "local");
    return modifyUser(employeeNo, new JSONObject().put("Valid", valid));
  }

  /** Re-assign the user to a different Hikvision user group. */
  public boolean setUserGroup(String employeeNo, String groupId, String groupName) throws IOException
  {
    return modifyUser(employeeNo,
        new JSONObject().put("userGroupNodeID", groupId).put("userGroupNodeName", groupName));
  }

  public boolean deleteUser(String employeeNo) throws IOException
  {
    JSONObject res = putJson("/ISAPI/AccessControl/UserInfo/Delete",
        new JSONObject().put("UserInfoDelCond",
            new JSONObject().put("EmployeeNoList",
                new JSONArray().put(new JSONObject().put("employeeNo", employeeNo)))));
    pace();
    return isOk(res);
  }

  public boolean createCard(String employeeNo, String cardNo) throws IOException
  {
    JSONObject c = new JSONObject()
        .put("employeeNo", employeeNo)
        .put("cardNo", cardNo)
        .put("cardType", "normalCard");
    JSONObject res = postJson("/ISAPI/AccessControl/CardInfo/Record",
        new JSONObject().put("CardInfo", c));
    pace();
    return isOk(res);
  }

  public boolean deleteCard(String cardNo) throws IOException
  {
    JSONObject res = putJson("/ISAPI/AccessControl/CardInfo/Delete",
        new JSONObject().put("CardInfoDelCond",
            new JSONObject().put("CardNoList",
                new JSONArray().put(new JSONObject().put("cardNo", cardNo)))));
    pace();
    return isOk(res);
  }

  public static boolean isOk(JSONObject res)
  {
    return res != null && res.optInt("statusCode", 0) == 1;
  }

  /** GET /ISAPI/System/deviceInfo — returns the raw XML body for diagnostic display. */
  public String getDeviceInfoXml() throws IOException
  {
    return request("GET", "/ISAPI/System/deviceInfo", null);
  }

  // ---------------------------------------------- TLS: trust self-signed

  /**
   * Trust-all SSLContext for self-signed Hikvision DS-K controllers.
   *
   * Uses {@link X509ExtendedTrustManager} — NOT the basic
   * {@link javax.net.ssl.X509TrustManager} — on purpose. The JDK's
   * {@code SSLContextImpl.AbstractTrustManagerWrapper} auto-wraps any
   * plain {@code X509TrustManager} to run {@code checkAdditionalTrust},
   * which performs SAN / hostname-IP validation even after our
   * trust-all method returns. The extended variant is treated as
   * "handles identity itself", so no wrapping happens — and our
   * permissive no-op behaviour wins.
   *
   * Combined with {@code SSLParameters.endpointIdentificationAlgorithm("")}
   * we get a full bypass: no cert chain check, no IP/SAN check. Only safe
   * because the controller is on the LAN behind digest auth.
   */
  private static SSLContext trustAllSslContext()
  {
    try
    {
      TrustManager[] tm = new TrustManager[] { new X509ExtendedTrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] c, String a) {}
        public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
        public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
        public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
        public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
      }};
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tm, new SecureRandom());
      return ctx;
    }
    catch (Exception e)
    {
      Logger.error("unable to build trust-all SSL context", e);
      throw new RuntimeException(e);
    }
  }
}
