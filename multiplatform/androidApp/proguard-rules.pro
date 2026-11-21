# Dependencies supply their consumer rules (including kotlinx.serialization).
# Add narrowly scoped rules here only for app code reached through reflection.

# OkHttp's AndroidCertificateChainCleaner wraps custom X509TrustManager in
# android.net.http.X509TrustManagerExtensions, which calls this 3-arg overload reflectively.
-keepclassmembers class team.bjtuss.bjtuselfservice.shared.network.AndroidSchoolTlsTrustManager {
    public java.util.List checkServerTrusted(java.security.cert.X509Certificate[], java.lang.String, java.lang.String);
    public boolean isSameTrustConfiguration(java.lang.String, java.lang.String);
}
