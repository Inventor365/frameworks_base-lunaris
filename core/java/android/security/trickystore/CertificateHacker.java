package android.security.trickystore;

import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.crypto.digests.SHA256Digest;
import com.android.internal.org.bouncycastle.crypto.params.ECDomainParameters;
import com.android.internal.org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import com.android.internal.org.bouncycastle.crypto.params.RSAKeyParameters;
import com.android.internal.org.bouncycastle.crypto.signers.ECDSASigner;
import com.android.internal.org.bouncycastle.crypto.signers.RSADigestSigner;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import com.android.internal.org.bouncycastle.jce.ECNamedCurveTable;
import com.android.internal.org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import com.android.internal.org.bouncycastle.jce.spec.ECParameterSpec;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @hide
 */
public final class CertificateHacker {
    private static final String TAG = "CertificateHacker";

    private static final Map<String, String> sLeafAlgorithms = new ConcurrentHashMap<>();

    private CertificateHacker() {}

    public static void clearLeafAlgorithms() {
        sLeafAlgorithms.clear();
    }

    public static Certificate[] hackCertificateChain(Certificate[] chain) {
        return hackCertificateChain(chain, null);
    }

    public static Certificate[] hackCertificateChain(Certificate[] chain, String[] packages) {
        if (chain == null || chain.length == 0) {
            return chain;
        }

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            X509Certificate leaf = (X509Certificate) certFactory.generateCertificate(
                new ByteArrayInputStream(chain[0].getEncoded()));

            byte[] extensionBytes = leaf.getExtensionValue(
                CertificateGenerator.ATTESTATION_OID.getId());
            if (extensionBytes == null) {
                return chain;
            }

            X509CertificateHolder leafHolder = new X509CertificateHolder(leaf.getEncoded());
            Extension extension = leafHolder.getExtension(CertificateGenerator.ATTESTATION_OID);
            ASN1Sequence sequence = ASN1Sequence.getInstance(extension.getExtnValue().getOctets());
            ASN1Encodable[] encodables = sequence.toArray();
            ASN1Sequence teeEnforced = (ASN1Sequence) encodables[7];

            ASN1EncodableVector vector = new ASN1EncodableVector();
            ASN1Encodable originalRootOfTrust = null;

            for (ASN1Encodable element : teeEnforced) {
                ASN1TaggedObject taggedObject = (ASN1TaggedObject) element;
                int tag = taggedObject.getTagNo();
                if (tag == 704) {
                    originalRootOfTrust = taggedObject.getBaseObject().toASN1Primitive();
                } else if (tag == 705 || tag == 706 || tag == 718 || tag == 719) {
                    // Skip to prevent duplicate tags when overridden values are appended
                    continue;
                } else {
                    vector.add(taggedObject);
                }
            }

            String algorithm = leaf.getPublicKey().getAlgorithm();
            KeyBoxManager keyboxManager = TrickyStoreService.getInstance().getKeyBoxManager();
            KeyBoxManager.KeyBox keybox = keyboxManager.getKeybox(algorithm);

            if (keybox == null) {
                Log.e(TAG, "No keybox for algorithm: " + algorithm);
                return chain;
            }

            List<Certificate> certificates = new ArrayList<>(keybox.certificates);
            X509CertificateHolder issuerHolder = new X509CertificateHolder(
                certificates.get(0).getEncoded());

            // Idempotency: if the leaf is already issued by this keybox, the chain was
            // already hacked (e.g. persisted into Keystore2 at key generation). Re-signing
            // it would corrupt the chain, so return it untouched.
            if (leafHolder.getIssuer().equals(issuerHolder.getSubject())) {
                return chain;
            }

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                issuerHolder.getSubject(),
                leafHolder.getSerialNumber(),
                leafHolder.getNotBefore(),
                leafHolder.getNotAfter(),
                leafHolder.getSubject(),
                leafHolder.getSubjectPublicKeyInfo()
            );

            PrivateKey privKey = keybox.keyPair.getPrivate();
            String sigAlg = (privKey instanceof RSAPrivateKey || privKey instanceof RSAPrivateCrtKey)
                    ? "SHA256withRSA"
                    : "SHA256withECDSA";
            ContentSigner signer = createBCSigner(sigAlg, privKey);

            Extension hackedExtension = hackAttestExtension(originalRootOfTrust, vector, encodables);
            builder.addExtension(hackedExtension);

            for (Object oid : leafHolder.getExtensions().getExtensionOIDs()) {
                String oidString = oid.toString();
                if (!oidString.equals(CertificateGenerator.ATTESTATION_OID.getId())) {
                    builder.addExtension(leafHolder.getExtension(
                        new ASN1ObjectIdentifier(oidString)));
                }
            }

            X509CertificateHolder builtHolder = builder.build(signer);
            X509Certificate hackedLeaf = (X509Certificate) certFactory.generateCertificate(
                new ByteArrayInputStream(builtHolder.getEncoded()));

            List<Certificate> result = new ArrayList<>();
            result.add(hackedLeaf);
            result.addAll(certificates);
            return result.toArray(new Certificate[0]);
        } catch (Exception e) {
            Log.e(TAG, "Failed to hack certificate chain", e);
            return chain;
        }
    }

    private static ContentSigner createBCSigner(String algorithm, PrivateKey privateKey) throws Exception {
        AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder().find(algorithm);
        
        if (privateKey instanceof BCECPrivateKey) {
            BCECPrivateKey bcKey = (BCECPrivateKey) privateKey;
            ECParameterSpec ecSpec = bcKey.getParameters();
            if (ecSpec != null) {
                ECDomainParameters domainParams = new ECDomainParameters(
                    ecSpec.getCurve(), ecSpec.getG(), ecSpec.getN(), ecSpec.getH());
                ECPrivateKeyParameters keyParam = new ECPrivateKeyParameters(bcKey.getD(), domainParams);
                return new ECContentSigner(sigAlgId, keyParam);
            } else {
                ECNamedCurveParameterSpec namedSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
                ECDomainParameters domainParams = new ECDomainParameters(
                    namedSpec.getCurve(), namedSpec.getG(), namedSpec.getN(), namedSpec.getH());
                ECPrivateKeyParameters keyParam = new ECPrivateKeyParameters(bcKey.getD(), domainParams);
                return new ECContentSigner(sigAlgId, keyParam);
            }
        } else if (privateKey instanceof ECPrivateKey) {
            ECPrivateKeyParameters keyParam = (ECPrivateKeyParameters) ECUtil.generatePrivateKeyParameter(privateKey);
            return new ECContentSigner(sigAlgId, keyParam);
        } else if (privateKey instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey rsaKey = (RSAPrivateCrtKey) privateKey;
            RSAKeyParameters keyParam = new RSAKeyParameters(true, rsaKey.getModulus(), rsaKey.getPrivateExponent());
            return new RSAContentSigner(sigAlgId, keyParam);
        } else if (privateKey instanceof RSAPrivateKey) {
            RSAPrivateKey rsaKey = (RSAPrivateKey) privateKey;
            RSAKeyParameters keyParam = new RSAKeyParameters(true, rsaKey.getModulus(), rsaKey.getPrivateExponent());
            return new RSAContentSigner(sigAlgId, keyParam);
        }
        
        throw new IllegalArgumentException("Unsupported key type: " + privateKey.getClass());
    }

    private static class ECContentSigner implements ContentSigner {
        private final AlgorithmIdentifier algId;
        private final ECPrivateKeyParameters keyParam;
        private final ByteArrayOutputStream stream;

        ECContentSigner(AlgorithmIdentifier algId, ECPrivateKeyParameters keyParam) {
            this.algId = algId;
            this.keyParam = keyParam;
            this.stream = new ByteArrayOutputStream();
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return algId;
        }

        @Override
        public OutputStream getOutputStream() {
            return stream;
        }

        @Override
        public byte[] getSignature() {
            try {
                byte[] data = stream.toByteArray();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(data);
                
                ECDSASigner signer = new ECDSASigner();
                signer.init(true, keyParam);
                BigInteger[] sig = signer.generateSignature(hash);
                
                ASN1EncodableVector v = new ASN1EncodableVector();
                v.add(new ASN1Integer(sig[0]));
                v.add(new ASN1Integer(sig[1]));
                return new DERSequence(v).getEncoded();
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate ECDSA signature", e);
            }
        }
    }

    private static class RSAContentSigner implements ContentSigner {
        private final AlgorithmIdentifier algId;
        private final RSAKeyParameters keyParam;
        private final ByteArrayOutputStream stream;

        RSAContentSigner(AlgorithmIdentifier algId, RSAKeyParameters keyParam) {
            this.algId = algId;
            this.keyParam = keyParam;
            this.stream = new ByteArrayOutputStream();
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return algId;
        }

        @Override
        public OutputStream getOutputStream() {
            return stream;
        }

        @Override
        public byte[] getSignature() {
            try {
                byte[] data = stream.toByteArray();
                RSADigestSigner signer = new RSADigestSigner(new SHA256Digest());
                signer.init(true, keyParam);
                signer.update(data, 0, data.length);
                return signer.generateSignature();
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate RSA signature", e);
            }
        }
    }

    private static boolean isAllZeros(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return true;
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }

    private static Extension hackAttestExtension(
            ASN1Encodable originalRootOfTrust,
            ASN1EncodableVector vector,
            ASN1Encodable[] originalEncodables) throws Exception {

        byte[] bootKey = null;
        byte[] bootHash = null;

        if (originalRootOfTrust instanceof ASN1Sequence) {
            try {
                ASN1Sequence rot = (ASN1Sequence) originalRootOfTrust;
                if (rot.size() >= 1 && rot.getObjectAt(0) instanceof DEROctetString) {
                    byte[] k = ((DEROctetString) rot.getObjectAt(0)).getOctets();
                    if (!isAllZeros(k)) {
                        bootKey = k;
                    }
                }
                if (rot.size() >= 4 && rot.getObjectAt(3) instanceof DEROctetString) {
                    byte[] h = ((DEROctetString) rot.getObjectAt(3)).getOctets();
                    if (!isAllZeros(h)) {
                        bootHash = h;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to extract root of trust elements from original", e);
            }
        }

        if (bootKey == null || isAllZeros(bootKey)) {
            bootKey = AttestationUtils.getBootKey();
        }
        if (bootHash == null || isAllZeros(bootHash)) {
            bootHash = AttestationUtils.getBootHash();
        }

        ASN1Encodable[] rootOfTrustElements = new ASN1Encodable[] {
            new DEROctetString(bootKey),
            ASN1Boolean.TRUE,
            new ASN1Enumerated(0),
            new DEROctetString(bootHash)
        };
        DERSequence hackedRootOfTrust = new DERSequence(rootOfTrustElements);

        // Emit in strictly ascending tag order as required by KeyMint
        java.util.TreeMap<Integer, ASN1Encodable> ordered = new java.util.TreeMap<>();
        for (int i = 0; i < vector.size(); i++) {
            ASN1TaggedObject t = (ASN1TaggedObject) vector.get(i);
            ordered.put(t.getTagNo(), t);
        }
        ordered.put(704, new DERTaggedObject(704, hackedRootOfTrust));
        ordered.put(705, new DERTaggedObject(true, 705, 
            new ASN1Integer(AttestationUtils.getOsVersion())));
        ordered.put(706, new DERTaggedObject(true, 706, 
            new ASN1Integer(AttestationUtils.getPatchLevel(false))));
        ordered.put(718, new DERTaggedObject(true, 718, 
            new ASN1Integer(AttestationUtils.getVendorPatchLevel(true))));
        ordered.put(719, new DERTaggedObject(true, 719, 
            new ASN1Integer(AttestationUtils.getBootPatchLevel(true))));

        ASN1EncodableVector sortedVector = new ASN1EncodableVector();
        for (ASN1Encodable enc : ordered.values()) {
            sortedVector.add(enc);
        }

        DERSequence hackedEnforced = new DERSequence(sortedVector);
        originalEncodables[7] = hackedEnforced;
        DERSequence hackedSequence = new DERSequence(originalEncodables);
        DEROctetString hackedOctets = new DEROctetString(hackedSequence);

        return new Extension(CertificateGenerator.ATTESTATION_OID, false, hackedOctets);
    }

    public static void storeLeafAlgorithm(String alias, int uid, String algorithm) {
        sLeafAlgorithms.put(alias + "_" + uid, algorithm);
    }

    public static String getLeafAlgorithm(String alias, int uid) {
        return sLeafAlgorithms.remove(alias + "_" + uid);
    }
}
