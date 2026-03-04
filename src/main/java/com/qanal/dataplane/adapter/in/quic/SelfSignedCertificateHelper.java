package com.qanal.dataplane.adapter.in.quic;

import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Generates a throwaway self-signed certificate for local development.
 * Never use in production.
 */
final class SelfSignedCertificateHelper {

    private static final SelfSignedCertificate CERT;

    static {
        try {
            CERT = new SelfSignedCertificate("qanal-dev.local");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static PrivateKey    privateKey()  { return CERT.key(); }
    static X509Certificate certificate() { return CERT.cert(); }

    private SelfSignedCertificateHelper() {}
}
