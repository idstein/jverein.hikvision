package de.jost_net.JVerein.hikvision;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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
    if (!verifySsl) b.sslContext(trustAllSslContext());
    this.http = b.build();
  }

  // ---------------------------------------------------------------- HTTP

  /** One round-trip with fresh digest. Throws on non-2xx final response. */
  public String request(String method, String path, String body) throws IOException
  {
    String url = baseUrl + path;
    HttpResponse<String> r1 = send(method, url, body, null);
    if (r1.statusCode() != 401) return r1.body();

    String challenge = r1.headers().firstValue("WWW-Authenticate").orElse(null);
    if (challenge == null)
      throw new IOException("no WWW-Authenticate header from " + url);

    String auth = buildDigestHeader(challenge, method, path);
    HttpResponse<String> r2 = send(method, url, body, auth);
    int code = r2.statusCode();
    if (code < 200 || code >= 300)
      throw new IOException("HTTP " + code + " on " + method + " " + path + ": " + r2.body());
    return r2.body();
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
    try { return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(e.getMessage()); }
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

  private void pace() { try { Thread.sleep(pauseMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

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

  public boolean createUser(String employeeNo, String name, String userType,
                            String groupId, String groupName, int regionPermissionGroup,
                            String gender) throws IOException
  {
    JSONObject u = new JSONObject();
    u.put("employeeNo", employeeNo);
    u.put("name", name);
    u.put("userType", userType);
    u.put("closeDelayEnabled", false);
    u.put("Valid", new JSONObject()
        .put("enable", false)
        .put("beginTime", "2000-01-01T00:00:00")
        .put("endTime", "2037-12-31T23:59:59")
        .put("timeType", "local"));
    u.put("belongGroup", "");
    u.put("password", "");
    u.put("localPassword", "");
    u.put("userGroupNodeID", groupId);
    u.put("userGroupNodeName", groupName);
    u.put("regionPermissionGroupIDList", new JSONArray().put(regionPermissionGroup));
    u.put("doorRight", "");
    u.put("maxOpenDoorTime", 0);
    u.put("openDoorTime", 0);
    if (gender != null && !gender.isEmpty()) u.put("gender", gender);
    JSONObject res = postJson("/ISAPI/AccessControl/UserInfo/Record",
        new JSONObject().put("UserInfo", u));
    pace();
    return isOk(res);
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

  private static SSLContext trustAllSslContext()
  {
    try
    {
      TrustManager[] tm = new TrustManager[] { new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] c, String a) {}
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
