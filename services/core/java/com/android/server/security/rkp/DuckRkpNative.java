/* SPDX-License-Identifier: MIT
 * CSR protocols adapted from eltavine/Duck-ToolBox
 * 8d6a2470b1033c35946b72bb1f9bc56d8947dd63 (including aviraxp's native V1).
 * Copyright (c) 2026 Eltavine (尉迟栩澜)
 * The corresponding MIT notice is included in frameworks/base/NOTICE.
 */
package com.android.server.security.rkp;

import static com.android.server.security.rkp.DuckRkpCbor.array;
import static com.android.server.security.rkp.DuckRkpCbor.arrayOf;
import static com.android.server.security.rkp.DuckRkpCbor.bytes;
import static com.android.server.security.rkp.DuckRkpCbor.decode;
import static com.android.server.security.rkp.DuckRkpCbor.encode;
import static com.android.server.security.rkp.DuckRkpCbor.integer;
import static com.android.server.security.rkp.DuckRkpCbor.map;
import static com.android.server.security.rkp.DuckRkpCbor.number;
import static com.android.server.security.rkp.DuckRkpCbor.text;

import android.os.ServiceManager;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import vendor.evo.duckrkp.IDuckRkp;

final class DuckRkpNative {
    private static final int LIMIT = 0xa000;
    private static final String SERVICE = "vendor.evo.duckrkp.IDuckRkp/default";
    private static final BigInteger ED25519_P =
            BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger ED25519_L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger ED25519_D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(ED25519_P)).mod(ED25519_P);
    private static final BigInteger ED25519_D2 =
            ED25519_D.multiply(BigInteger.TWO).mod(ED25519_P);
    private static final BigInteger ED25519_I = BigInteger.TWO.modPow(
            ED25519_P.subtract(BigInteger.ONE).shiftRight(2), ED25519_P);

    private DuckRkpNative() {}

    static boolean available() {
        return ServiceManager.isDeclared(SERVICE);
    }

    static byte[] signV2(Map publicKey, byte[] challenge) throws Exception {
        byte[] response = request(2, encode(new Map().put(number(4), bytes(challenge))
                .put(number(57), bytes(encode(arrayOf(publicKey))))));
        try {
            DataItem item = decode(response);
            if (item instanceof Map) {
                Map wrapper = map(item);
                require(wrapper.getKeys().size() == 1);
                response = bytes(wrapper.get(number(61)));
            }
            verifyV2(response, publicKey, challenge);
        } catch (Exception e) {
            throw new DuckRkpV1.Failure("invalid_hal_response");
        }
        return response;
    }

    static byte[] signV1(Map publicKey, byte[] challenge, byte[] eek, String fingerprint)
            throws Exception {
        byte[] macKey = new byte[32];
        new SecureRandom().nextBytes(macKey);
        byte[] headers = encode(new Map().put(number(1), number(5)));
        byte[] structure = encode(arrayOf(text("MAC0"), bytes(headers), bytes(new byte[0]),
                bytes(encode(arrayOf(publicKey)))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        byte[] tag = mac.doFinal(structure);
        byte[] payload = encode(new Map().put(number(4), bytes(challenge))
                .put(number(55), number(0)).put(number(56), bytes(macKey))
                .put(number(57), bytes(tag)).put(number(58), bytes(eek)));
        Arrays.fill(macKey, (byte) 0);
        Map response;
        try {
            response = map(decode(request(1, payload)));
            require(response.getKeys().size() == 2);
            return DuckRkpV1.assemble(publicKey, challenge, tag, bytes(response.get(number(59))),
                    bytes(response.get(number(60))), fingerprint);
        } catch (DuckRkpV1.Failure e) {
            throw e;
        } catch (Exception e) {
            throw new DuckRkpV1.Failure("invalid_hal_response");
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static byte[] request(int backend, byte[] payload) throws Exception {
        if (payload.length == 0 || payload.length > 0x9000 - 4) {
            throw new DuckRkpV1.Failure("invalid_hal_response");
        }
        IDuckRkp service = IDuckRkp.Stub.asInterface(ServiceManager.checkService(SERVICE));
        if (service == null) throw new DuckRkpV1.Failure("native_unavailable");
        try {
            byte[] reply = service.request(backend, payload);
            if (reply == null || reply.length < 12 || reply.length > LIMIT + 12) {
                throw new DuckRkpV1.Failure("invalid_hal_response");
            }
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(reply));
            int status = input.readInt();
            int detail = input.readInt();
            int size = input.readInt();
            if (status != 0) {
                throw new DuckRkpV1.Failure(status == 5 ? "native_timeout"
                        : status == 4 ? "native_rejected"
                        : status == 2 ? "native_unavailable" : "native_transport", detail);
            }
            if (size <= 0 || size > LIMIT - 8 || size != reply.length - 12) {
                throw new DuckRkpV1.Failure("invalid_hal_response");
            }
            byte[] response = new byte[size];
            input.readFully(response);
            return response;
        } catch (android.os.RemoteException | java.io.IOException e) {
            throw new DuckRkpV1.Failure("native_transport");
        }
    }

    static void verifyV2(byte[] csr, Map expectedKey, byte[] challenge) throws Exception {
        List<DataItem> request = array(decode(csr));
        require(request.size() == 4 && integer(request.get(0)) == 1);
        Map certificates = map(request.get(1));
        for (DataItem issuer : certificates.getKeys()) {
            require(issuer instanceof UnicodeString);
            List<DataItem> chain = array(certificates.get(issuer));
            require(!chain.isEmpty() && chain.size() <= 16);
            for (DataItem certificate : chain) require(bytes(certificate).length > 0);
        }
        List<DataItem> dice = array(request.get(2));
        require(dice.size() >= 2 && dice.size() <= 32);
        Map key = map(dice.get(0));
        for (int i = 1; i < dice.size(); i++) {
            Map claims = map(decode(verifySign1(dice.get(i), key)));
            require(claims.get(number(1)) instanceof UnicodeString
                    && claims.get(number(2)) instanceof UnicodeString);
            require(bytes(claims.get(number(-4670553))).length > 0);
            key = map(decode(bytes(claims.get(number(-4670552)))));
        }
        List<DataItem> signed = array(decode(verifySign1(request.get(3), key)));
        require(signed.size() == 2 && Arrays.equals(bytes(signed.get(0)), challenge));
        List<DataItem> contents = array(decode(bytes(signed.get(1))));
        require(contents.size() == 4 && integer(contents.get(0)) == 3
                && text("keymint").equals(contents.get(1))
                && !map(contents.get(2)).getKeys().isEmpty());
        List<DataItem> keys = array(contents.get(3));
        require(keys.size() == 1 && expectedKey.equals(map(keys.get(0))));
    }

    private static byte[] verifySign1(DataItem item, Map key) throws Exception {
        List<DataItem> sign1 = array(item);
        require(sign1.size() == 4);
        byte[] protectedBytes = bytes(sign1.get(0));
        Map headers = map(decode(protectedBytes));
        Map unprotected = map(sign1.get(1));
        require(!headers.getKeys().contains(number(2))
                && !unprotected.getKeys().contains(number(2))
                && !unprotected.getKeys().contains(number(1)));
        for (DataItem label : headers.getKeys()) require(!unprotected.getKeys().contains(label));
        int algorithm = integer(key.get(number(3)));
        require(integer(headers.get(number(1))) == algorithm);
        byte[] payload = bytes(sign1.get(2));
        byte[] signature = bytes(sign1.get(3));
        byte[] signed = encode(arrayOf(text("Signature1"), bytes(protectedBytes),
                bytes(new byte[0]), bytes(payload)));
        if (algorithm == -8) {
            require(verifyEd25519(ed25519Key(key), signed, signature));
            return payload;
        }
        PublicKey publicKey = publicKey(key);
        String name = algorithm == -7 ? "SHA256withECDSA" : "SHA384withECDSA";
        Signature verifier = Signature.getInstance(name);
        verifier.initVerify(publicKey);
        verifier.update(signed);
        require(verifier.verify(derSignature(signature, algorithm == -7 ? 32 : 48)));
        return payload;
    }

    private static byte[] ed25519Key(Map key) throws Exception {
        require(integer(key.get(number(1))) == 1 && integer(key.get(number(3))) == -8
                && integer(key.get(number(-1))) == 6 && !key.getKeys().contains(number(-3)));
        byte[] x = bytes(key.get(number(-2)));
        require(x.length == 32);
        return x;
    }

    private static boolean verifyEd25519(byte[] publicKey, byte[] message, byte[] signature)
            throws Exception {
        if (publicKey.length != 32 || signature.length != 64) return false;
        BigInteger[] a = decodeEd25519Point(publicKey);
        BigInteger[] r = decodeEd25519Point(Arrays.copyOfRange(signature, 0, 32));
        BigInteger s = littleEndian(signature, 32, 32);
        if (s.compareTo(ED25519_L) >= 0) return false;
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        sha512.update(signature, 0, 32);
        sha512.update(publicKey);
        sha512.update(message);
        BigInteger k = littleEndian(sha512.digest(), 0, 64).mod(ED25519_L);
        BigInteger by = BigInteger.valueOf(4)
                .multiply(BigInteger.valueOf(5).modInverse(ED25519_P)).mod(ED25519_P);
        BigInteger bx = recoverEd25519X(by, false);
        BigInteger[] base = {bx, by, BigInteger.ONE, bx.multiply(by).mod(ED25519_P)};
        BigInteger[] lhs = ed25519ScalarMult(base, s);
        BigInteger[] rhs = ed25519Add(r, ed25519ScalarMult(a, k));
        return lhs[0].multiply(rhs[2]).subtract(rhs[0].multiply(lhs[2]))
                        .mod(ED25519_P).equals(BigInteger.ZERO)
                && lhs[1].multiply(rhs[2]).subtract(rhs[1].multiply(lhs[2]))
                        .mod(ED25519_P).equals(BigInteger.ZERO);
    }

    private static BigInteger littleEndian(byte[] bytes, int offset, int length) {
        byte[] reversed = new byte[length];
        for (int i = 0; i < length; i++) reversed[i] = bytes[offset + length - 1 - i];
        return new BigInteger(1, reversed);
    }

    private static BigInteger[] decodeEd25519Point(byte[] encoded) throws Exception {
        require(encoded.length == 32);
        byte[] reversed = new byte[32];
        for (int i = 0; i < 32; i++) reversed[i] = encoded[31 - i];
        boolean sign = (reversed[0] & 0x80) != 0;
        reversed[0] &= 0x7f;
        BigInteger y = new BigInteger(1, reversed);
        require(y.compareTo(ED25519_P) < 0);
        BigInteger x = recoverEd25519X(y, sign);
        return new BigInteger[] {x, y, BigInteger.ONE, x.multiply(y).mod(ED25519_P)};
    }

    private static BigInteger recoverEd25519X(BigInteger y, boolean sign) throws Exception {
        BigInteger y2 = y.multiply(y).mod(ED25519_P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(ED25519_P);
        BigInteger v = ED25519_D.multiply(y2).add(BigInteger.ONE).mod(ED25519_P);
        BigInteger xx = u.multiply(v.modInverse(ED25519_P)).mod(ED25519_P);
        BigInteger x = xx.modPow(ED25519_P.add(BigInteger.valueOf(3)).shiftRight(3), ED25519_P);
        if (!x.multiply(x).subtract(xx).mod(ED25519_P).equals(BigInteger.ZERO)) {
            x = x.multiply(ED25519_I).mod(ED25519_P);
        }
        require(x.multiply(x).subtract(xx).mod(ED25519_P).equals(BigInteger.ZERO));
        if (x.equals(BigInteger.ZERO)) {
            require(!sign);
        } else if (x.testBit(0) != sign) {
            x = ED25519_P.subtract(x);
        }
        return x;
    }

    private static BigInteger[] ed25519Add(BigInteger[] p1, BigInteger[] p2) {
        BigInteger a = p1[1].subtract(p1[0]).multiply(p2[1].subtract(p2[0])).mod(ED25519_P);
        BigInteger b = p1[1].add(p1[0]).multiply(p2[1].add(p2[0])).mod(ED25519_P);
        BigInteger c = p1[3].multiply(ED25519_D2).mod(ED25519_P).multiply(p2[3]).mod(ED25519_P);
        BigInteger d = p1[2].multiply(BigInteger.TWO).multiply(p2[2]).mod(ED25519_P);
        BigInteger e = b.subtract(a).mod(ED25519_P);
        BigInteger f = d.subtract(c).mod(ED25519_P);
        BigInteger g = d.add(c).mod(ED25519_P);
        BigInteger h = b.add(a).mod(ED25519_P);
        return new BigInteger[] {
                e.multiply(f).mod(ED25519_P),
                g.multiply(h).mod(ED25519_P),
                f.multiply(g).mod(ED25519_P),
                e.multiply(h).mod(ED25519_P)
        };
    }

    private static BigInteger[] ed25519ScalarMult(BigInteger[] point, BigInteger scalar) {
        BigInteger[] acc = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        BigInteger[] cur = point;
        for (int i = 0; i < scalar.bitLength(); i++) {
            if (scalar.testBit(i)) {
                acc = ed25519Add(acc, cur);
            }
            cur = ed25519Add(cur, cur);
        }
        return acc;
    }

    private static PublicKey publicKey(Map key) throws Exception {
        int type = integer(key.get(number(1)));
        int algorithm = integer(key.get(number(3)));
        int curve = integer(key.get(number(-1)));
        byte[] x = bytes(key.get(number(-2)));
        require(type == 2 && ((algorithm == -7 && curve == 1 && x.length == 32)
                || (algorithm == -35 && curve == 2 && x.length == 48)));
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(curve == 1 ? "secp256r1" : "secp384r1"));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        BigInteger prime = ((java.security.spec.ECFieldFp) spec.getCurve().getField()).getP();
        BigInteger px = new BigInteger(1, x);
        BigInteger rhs = px.pow(3).add(spec.getCurve().getA().multiply(px))
                .add(spec.getCurve().getB()).mod(prime);
        DataItem encodedY = key.get(number(-3));
        BigInteger py;
        if (SimpleValue.TRUE.equals(encodedY) || SimpleValue.FALSE.equals(encodedY)) {
            py = rhs.modPow(prime.add(BigInteger.ONE).shiftRight(2), prime);
            if (py.testBit(0) != SimpleValue.TRUE.equals(encodedY)) py = prime.subtract(py);
        } else {
            byte[] y = bytes(encodedY);
            require(y.length == x.length);
            py = new BigInteger(1, y);
        }
        require(px.compareTo(prime) < 0 && py.compareTo(prime) < 0
                && py.pow(2).mod(prime).equals(rhs));
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(px, py), spec));
    }

    private static byte[] derSignature(byte[] raw, int width) throws Exception {
        require(raw.length == width * 2);
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, width)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, width, raw.length)).toByteArray();
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (der.length - 2);
        der[2] = 2;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 2;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }

    private static void require(boolean condition) throws Exception {
        if (!condition) throw new java.io.IOException("Invalid native CSR");
    }
}
