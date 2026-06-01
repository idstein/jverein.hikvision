package de.jost_net.JVerein.hikvision;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONArray;
import org.json.JSONObject;

import de.willuhn.logging.Logger;

/**
 * Hikvision DS-K2702WX ISAPI client.
 *
 * Implements HTTP Digest auth manually because the controller invalidates
 * the nonce after each request — a Java HttpClient that caches the
 * challenge would fail on the second call. Every request does a fresh
 * 401 → challenge → re-send dance.
 *
 * The self-signed controller cert is trusted unconditionally (this is a
 * LAN-only device behind digest auth).
 *
 * Pace requests by Settings.getInterCallPauseMs() between batches; the
 * controller's concurrent-session pool is small and rejects burst traffic
 * with HTTP 401 wrapped XML (not a real auth failure).
 */
public class HikvisionClient
{
  private final String baseUrl;
  private final String user;
  private final String password;
  private final int pauseMs;

  public HikvisionClient(String baseUrl, String user, String password, int pauseMs)
  {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.user = user;
    this.password = password;
    this.pauseMs = pauseMs;
    installTrustAll();
  }

  // ---------------------------------------------------------------- HTTP

  private static final SecureRandom RAND = new SecureRandom();

  /**
   * Perform an HTTP request with a fresh digest challenge-response per call.
   * Throws if the second response is not 200/201.
   */
  public String request(String method, String path, String body) throws IOException
  {
    String url = baseUrl + path;
    // step 1: get the challenge
    HttpURLConnection conn = open(url, method, null, body);
    int code = conn.getResponseCode();
    if (code != 401)
    {
      return readBody(conn);
    }
    String challenge = conn.getHeaderField("WWW-Authenticate");
    drain(conn);
    if (challenge == null) throw new IOException("no WWW-Authenticate header from " + url);

    // step 2: build the digest response and resend
    String auth = buildDigestHeader(challenge, method, path, body);
    HttpURLConnection conn2 = open(url, method, auth, body);
    int code2 = conn2.getResponseCode();
    String response = readBody(conn2);
    if (code2 < 200 || code2 >= 300)
    {
      // Hikvision returns 200 even for failed semantic operations (with statusCode in body),
      // so a non-2xx here is an HTTP-level failure.
      throw new IOException("HTTP " + code2 + " on " + method + " " + path + ": " + response);
    }
    return response;
  }

  private HttpURLConnection open(String urlStr, String method, String authHeader, String body)
      throws IOException
  {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod(method);
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(20000);
    if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
    if (body != null)
    {
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json");
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      conn.setFixedLengthStreamingMode(payload.length);
      try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
    }
    return conn;
  }

  private String readBody(HttpURLConnection conn) throws IOException
  {
    InputStream is = conn.getErrorStream();
    if (is == null) is = conn.getInputStream();
    if (is == null) return "";
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = is.read(buf)) >= 0) baos.write(buf, 0, n);
    return baos.toString(StandardCharsets.UTF_8);
  }

  private void drain(HttpURLConnection conn)
  {
    try (InputStream is = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream())
    {
      if (is != null) { byte[] buf = new byte[1024]; while (is.read(buf) >= 0) {} }
    }
    catch (Exception ignored) {}
  }

  // ---------------------------------------------------------- Digest auth

  private static final Pattern KV = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");

  private String buildDigestHeader(String challenge, String method, String path, String body)
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
    String response;
    if (qop != null && (qop.equals("auth") || qop.contains("auth")))
    {
      response = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
    }
    else
    {
      response = md5Hex(ha1 + ":" + nonce + ":" + ha2);
    }

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
    String raw = request("POST", path + "?format=json", body.toString());
    return new JSONObject(raw);
  }

  private JSONObject putJson(String path, JSONObject body) throws IOException
  {
    String raw = request("PUT", path + "?format=json", body.toString());
    return new JSONObject(raw);
  }

  /** All users on the controller — paged at 200 per call. */
  public JSONArray listAllUsers() throws IOException
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
      if (got == 0 || out.length() >= total) break;
      pos += got;
      pace();
    }
    return out;
  }

  /** All cards on the controller — paged at 200 per call. */
  public JSONArray listAllCards() throws IOException
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
      if (got == 0 || out.length() >= total) break;
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

  // ---------------------------------------------- TLS: trust self-signed

  private static boolean trustAllInstalled = false;
  private static synchronized void installTrustAll()
  {
    if (trustAllInstalled) return;
    try
    {
      TrustManager[] tm = new TrustManager[] { new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] c, String a) {}
      }};
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tm, new SecureRandom());
      HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
      HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
      trustAllInstalled = true;
    }
    catch (Exception e) { Logger.error("unable to install trust-all TLS", e); }
  }
}
