package web.controllers;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/// Something written down where the client keeps it, that the client cannot
/// change.
///
/// A session and a CSRF token are the same problem: a server hands a value to a
/// browser, the browser hands it back, and the server has to know whether it is
/// the value it wrote. Signing it is what makes that answerable without storing
/// anything, so a server can be restarted, or be three servers, and the answer
/// does not change.
///
/// Two details do all the work. The comparison is constant-time, because one
/// that returns early tells an attacker how much of a forged tag was right, and
/// that is enough to build the rest a byte at a time. And what is signed is the
/// payload *including* whatever the server considers its expiry, so a client
/// that would like a longer session has to forge a signature rather than edit a
/// number.
public final class Signature {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    private Signature(byte[] secret) {
        if (secret.length < 16) {
            throw new ControllerException("a signing secret needs at least 16 bytes, this one has " + secret.length);
        }
        this.secret = secret.clone();
    }

    public static Signature of(byte[] secret) {
        return new Signature(secret);
    }

    public static Signature of(String secret) {
        return new Signature(secret.getBytes(StandardCharsets.UTF_8));
    }

    /// The payload and its tag, in a form a cookie can hold.
    public String sign(String payload) {
        var encoded = encode(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + encode(tag(encoded));
    }

    /// The payload back, if this is one of ours. A wrong tag, a missing one, or
    /// anything that is not the shape of a signed value is the same answer:
    /// nothing. There is no version of this that reports *why*, because the only
    /// caller who wants to know is the one guessing.
    public Optional<String> verify(String signed) {
        if (signed == null) return Optional.empty();
        var dot = signed.lastIndexOf('.');
        if (dot < 0) return Optional.empty();
        var payload = signed.substring(0, dot);
        try {
            var offered = decode(signed.substring(dot + 1));
            if (!MessageDigest.isEqual(tag(payload), offered)) return Optional.empty();
            return Optional.of(new String(decode(payload), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private byte[] tag(String payload) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new ControllerException("this runtime cannot " + ALGORITHM + ": " + e.getMessage());
        }
    }

    /// Base64 without padding, since `=` is legal in a cookie value but reads
    /// like a separator to everything that has ever parsed one by hand.
    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String text) {
        return Base64.getUrlDecoder().decode(text);
    }
}
