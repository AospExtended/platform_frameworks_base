/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.util.apk;

import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.PackageParser.SigningDetails.SignatureSchemeVersion;
import android.content.pm.Signature;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.jar.StrictJarFile;
import android.util.BoostFramework;

import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestException;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Facade class that takes care of the details of APK verification on
 * behalf of PackageParser.
 *
 * @hide for internal use only.
 */
public class ApkSignatureVerifier {

    private static final AtomicReference<byte[]> sBuffer = new AtomicReference<>();

    private static final String TAG = "ApkSignatureVerifier";
    // multithread verification
    private static final int NUMBER_OF_CORES =
            Runtime.getRuntime().availableProcessors() >= 4 ? 4 : Runtime.getRuntime().availableProcessors() ;
    private static BoostFramework sPerfBoost = null;
    private static boolean sIsPerfLockAcquired = false;
    /**
     * Verifies the provided APK and returns the certificates associated with each signer.
     *
     * @throws PackageParserException if the APK's signature failed to verify.
     */
    public static PackageParser.SigningDetails verify(String apkPath,
            @SignatureSchemeVersion int minSignatureSchemeVersion)
            throws PackageParserException {

        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V3) {
            // V3 and before are older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
            + " or newer for package " + apkPath);
        }

        // first try v3
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "verifyV3");
        try {
            ApkSignatureSchemeV3Verifier.VerifiedSigner vSigner =
                    ApkSignatureSchemeV3Verifier.verify(apkPath);
            Certificate[][] signerCerts = new Certificate[][] { vSigner.certs };
            Signature[] signerSigs = convertToSignatures(signerCerts);
            Signature[] pastSignerSigs = null;
            int[] pastSignerSigsFlags = null;
            if (vSigner.por != null) {
                // populate proof-of-rotation information
                pastSignerSigs = new Signature[vSigner.por.certs.size()];
                pastSignerSigsFlags = new int[vSigner.por.flagsList.size()];
                for (int i = 0; i < pastSignerSigs.length; i++) {
                    pastSignerSigs[i] = new Signature(vSigner.por.certs.get(i).getEncoded());
                    pastSignerSigsFlags[i] = vSigner.por.flagsList.get(i);
                }
            }
            return new PackageParser.SigningDetails(
                    signerSigs, SignatureSchemeVersion.SIGNING_BLOCK_V3,
                    pastSignerSigs, pastSignerSigsFlags);
        } catch (SignatureNotFoundException e) {
            // not signed with v3, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V3) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v3 signature in package " + apkPath, e);
            }
        } catch (Exception e) {
            // APK Signature Scheme v2 signature found but did not verify
            throw new  PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v3", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // redundant, protective version check
        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V2) {
            // V2 and before are older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // try v2
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "verifyV2");
        try {
            Certificate[][] signerCerts = ApkSignatureSchemeV2Verifier.verify(apkPath);
            Signature[] signerSigs = convertToSignatures(signerCerts);

            return new PackageParser.SigningDetails(
                    signerSigs, SignatureSchemeVersion.SIGNING_BLOCK_V2);
        } catch (SignatureNotFoundException e) {
            // not signed with v2, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V2) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v2 signature in package " + apkPath, e);
            }
        } catch (Exception e) {
            // APK Signature Scheme v2 signature found but did not verify
            throw new  PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v2", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // redundant, protective version check
        if (minSignatureSchemeVersion > SignatureSchemeVersion.JAR) {
            // V1 and is older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // v2 didn't work, try jarsigner
        return verifyV1Signature(apkPath, true);
    }

    /**
     * Verifies the provided APK and returns the certificates associated with each signer.
     *
     * @param verifyFull whether to verify all contents of this APK or just collect certificates.
     *
     * @throws PackageParserException if there was a problem collecting certificates
     */
    private static PackageParser.SigningDetails verifyV1Signature(
            String apkPath, boolean verifyFull)
            throws PackageParserException {
        int objectNumber = verifyFull ? NUMBER_OF_CORES : 1;
        StrictJarFile[] jarFile = new StrictJarFile[objectNumber];
        final ArrayMap<String, StrictJarFile> strictJarFiles = new ArrayMap<String, StrictJarFile>();
        try {
            final Certificate[][] lastCerts;
            final Signature[] lastSigs;

            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "strictJarFileCtor");

            if (sPerfBoost == null) {
                sPerfBoost = new BoostFramework();
            }
            if (sPerfBoost != null && !sIsPerfLockAcquired) {
                //Use big enough number here to hold the perflock for entire PackageInstall session
                sPerfBoost.perfHint(BoostFramework.VENDOR_HINT_PACKAGE_INSTALL_BOOST,
                        null, Integer.MAX_VALUE, -1);
                Slog.d(TAG, "Perflock acquired for PackageInstall ");
                sIsPerfLockAcquired = true;
            }
            // we still pass verify = true to ctor to collect certs, even though we're not checking
            // the whole jar.
            for (int i = 0; i < objectNumber; i++) {
                jarFile[i] = new StrictJarFile(
                        apkPath,
                        true, // collect certs
                        verifyFull); // whether to reject APK with stripped v2 signatures (b/27887819)
            }
            final List<ZipEntry> toVerify = new ArrayList<>();

            // Gather certs from AndroidManifest.xml, which every APK must have, as an optimization
            // to not need to verify the whole APK when verifyFUll == false.
            final ZipEntry manifestEntry = jarFile[0].findEntry(
                    PackageParser.ANDROID_MANIFEST_FILENAME);
            if (manifestEntry == null) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                        "Package " + apkPath + " has no manifest");
            }
            lastCerts = loadCertificates(jarFile[0], manifestEntry);
            if (ArrayUtils.isEmpty(lastCerts)) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES, "Package "
                        + apkPath + " has no certificates at entry "
                        + PackageParser.ANDROID_MANIFEST_FILENAME);
            }
            lastSigs = convertToSignatures(lastCerts);

            // fully verify all contents, except for AndroidManifest.xml  and the META-INF/ files.
            if (verifyFull) {
                final Iterator<ZipEntry> i = jarFile[0].iterator();
                while (i.hasNext()) {
                    final ZipEntry entry = i.next();
                    if (entry.isDirectory()) continue;

                    final String entryName = entry.getName();
                    if (entryName.startsWith("META-INF/")) continue;
                    if (entryName.equals(PackageParser.ANDROID_MANIFEST_FILENAME)) continue;

                    toVerify.add(entry);
                }
                class VerificationData {
                    public Exception exception;
                    public int exceptionFlag;
                    public boolean wait;
                    public int index;
                    public Object objWaitAll;
                }
                VerificationData vData = new VerificationData();
                vData.objWaitAll = new Object();
                final ThreadPoolExecutor verificationExecutor = new ThreadPoolExecutor(
                        NUMBER_OF_CORES,
                        NUMBER_OF_CORES,
                        1,/*keep alive time*/
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<Runnable>());
                for (ZipEntry entry : toVerify) {
                    Runnable verifyTask = new Runnable(){
                        public void run() {
                            try {
                                String tid = Long.toString(Thread.currentThread().getId());
                                StrictJarFile tempJarFile;
                                synchronized (strictJarFiles) {
                                    tempJarFile = strictJarFiles.get(tid);
                                    if (tempJarFile == null) {
                                        tempJarFile = jarFile[vData.index++];
                                        strictJarFiles.put(tid, tempJarFile);
                                    }
                                }
                                final Certificate[][] entryCerts = loadCertificates(tempJarFile, entry);
                                if (ArrayUtils.isEmpty(entryCerts)) {
                                    throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                                            "Package " + apkPath + " has no certificates at entry "
                                            + entry.getName());
                                }

                                // make sure all entries use the same signing certs
                                final Signature[] entrySigs = convertToSignatures(entryCerts);
                                if (!Signature.areExactMatch(lastSigs, entrySigs)) {
                                    throw new PackageParserException(
                                            INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                                            "Package " + apkPath + " has mismatched certificates at entry "
                                            + entry.getName());
                                }
                            } catch (GeneralSecurityException e) {
                                synchronized (vData.objWaitAll) {
                                    vData.exceptionFlag = INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING;
                                    vData.exception = e;
                                    //Slog.w(TAG, "verifyV1 GeneralSecurityException " + vData.exceptionFlag);
                                }
                            } catch (PackageParserException e) {
                                synchronized (vData.objWaitAll) {
                                    vData.exceptionFlag = INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
                                    vData.exception = e;
                                    //Slog.w(TAG, "verifyV1 PackageParserException " + vData.exceptionFlag);
                                }
                            }
                        }};
                    synchronized (vData.objWaitAll) {
                        if (vData.exceptionFlag == 0) {
                            verificationExecutor.execute(verifyTask);
                        }
                    }
                }
                vData.wait = true;
                verificationExecutor.shutdown();
                while (vData.wait && vData.exceptionFlag == 0){
                    try {
                        vData.wait = !verificationExecutor.awaitTermination(50,
                                TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Slog.w(TAG,"VerifyV1 interrupted while awaiting all threads done...");
                    }
                }
                if (vData.wait) {
                    Slog.w(TAG, "verifyV1 Exception " + vData.exceptionFlag);
                    verificationExecutor.shutdownNow();
                }
                if (vData.exceptionFlag != 0)
                    throw new PackageParserException(vData.exceptionFlag,
                            "Failed to collect certificates from " + apkPath, vData.exception);
            }
            return new PackageParser.SigningDetails(lastSigs, SignatureSchemeVersion.JAR);
        } catch (GeneralSecurityException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING,
                    "Failed to collect certificates from " + apkPath, e);
        } catch (IOException | RuntimeException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath, e);
        } finally {
            if (sIsPerfLockAcquired && sPerfBoost != null) {
                sPerfBoost.perfLockRelease();
                sIsPerfLockAcquired = false;
                Slog.d(TAG, "Perflock released for PackageInstall ");
            }
            strictJarFiles.clear();
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            for (int i = 0; i < objectNumber ; i++) {
                closeQuietly(jarFile[i]);
            }
        }
    }

    private static Certificate[][] loadCertificates(StrictJarFile jarFile, ZipEntry entry)
            throws PackageParserException {
        InputStream is = null;
        try {
            // We must read the stream for the JarEntry to retrieve
            // its certificates.
            is = jarFile.getInputStream(entry);
            readFullyIgnoringContents(is);
            return jarFile.getCertificateChains(entry);
        } catch (IOException | RuntimeException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed reading " + entry.getName() + " in " + jarFile, e);
        } finally {
            IoUtils.closeQuietly(is);
        }
    }

    private static void readFullyIgnoringContents(InputStream in) throws IOException {
        byte[] buffer = sBuffer.getAndSet(null);
        if (buffer == null) {
            buffer = new byte[4096];
        }

        int n = 0;
        int count = 0;
        while ((n = in.read(buffer, 0, buffer.length)) != -1) {
            count += n;
        }

        sBuffer.set(buffer);
        return;
    }

    /**
     * Converts an array of certificate chains into the {@code Signature} equivalent used by the
     * PackageManager.
     *
     * @throws CertificateEncodingException if it is unable to create a Signature object.
     */
    public static Signature[] convertToSignatures(Certificate[][] certs)
            throws CertificateEncodingException {
        final Signature[] res = new Signature[certs.length];
        for (int i = 0; i < certs.length; i++) {
            res[i] = new Signature(certs[i]);
        }
        return res;
    }

    private static void closeQuietly(StrictJarFile jarFile) {
        if (jarFile != null) {
            try {
                jarFile.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Returns the certificates associated with each signer for the given APK without verification.
     * This method is dangerous and should not be used, unless the caller is absolutely certain the
     * APK is trusted.
     *
     * @throws PackageParserException if the APK's signature failed to verify.
     * or greater is not found, except in the case of no JAR signature.
     */
    public static PackageParser.SigningDetails plsCertsNoVerifyOnlyCerts(
            String apkPath, int minSignatureSchemeVersion)
            throws PackageParserException {

        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V3) {
            // V3 and before are older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // first try v3
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "certsOnlyV3");
        try {
            ApkSignatureSchemeV3Verifier.VerifiedSigner vSigner =
                    ApkSignatureSchemeV3Verifier.plsCertsNoVerifyOnlyCerts(apkPath);
            Certificate[][] signerCerts = new Certificate[][] { vSigner.certs };
            Signature[] signerSigs = convertToSignatures(signerCerts);
            Signature[] pastSignerSigs = null;
            int[] pastSignerSigsFlags = null;
            if (vSigner.por != null) {
                // populate proof-of-rotation information
                pastSignerSigs = new Signature[vSigner.por.certs.size()];
                pastSignerSigsFlags = new int[vSigner.por.flagsList.size()];
                for (int i = 0; i < pastSignerSigs.length; i++) {
                    pastSignerSigs[i] = new Signature(vSigner.por.certs.get(i).getEncoded());
                    pastSignerSigsFlags[i] = vSigner.por.flagsList.get(i);
                }
            }
            return new PackageParser.SigningDetails(
                    signerSigs, SignatureSchemeVersion.SIGNING_BLOCK_V3,
                    pastSignerSigs, pastSignerSigsFlags);
        } catch (SignatureNotFoundException e) {
            // not signed with v3, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V3) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v3 signature in package " + apkPath, e);
            }
        } catch (Exception e) {
            // APK Signature Scheme v3 signature found but did not verify
            throw new  PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v3", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // redundant, protective version check
        if (minSignatureSchemeVersion > SignatureSchemeVersion.SIGNING_BLOCK_V2) {
            // V2 and before are older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // first try v2
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "certsOnlyV2");
        try {
            Certificate[][] signerCerts =
                    ApkSignatureSchemeV2Verifier.plsCertsNoVerifyOnlyCerts(apkPath);
            Signature[] signerSigs = convertToSignatures(signerCerts);
            return new PackageParser.SigningDetails(signerSigs,
                    SignatureSchemeVersion.SIGNING_BLOCK_V2);
        } catch (SignatureNotFoundException e) {
            // not signed with v2, try older if allowed
            if (minSignatureSchemeVersion >= SignatureSchemeVersion.SIGNING_BLOCK_V2) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                        "No APK Signature Scheme v2 signature in package " + apkPath, e);
            }
        } catch (Exception e) {
            // APK Signature Scheme v2 signature found but did not verify
            throw new  PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to collect certificates from " + apkPath
                            + " using APK Signature Scheme v2", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // redundant, protective version check
        if (minSignatureSchemeVersion > SignatureSchemeVersion.JAR) {
            // V1 and is older than the requested minimum signing version
            throw new PackageParserException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "No signature found in package of version " + minSignatureSchemeVersion
                            + " or newer for package " + apkPath);
        }

        // v2 didn't work, try jarsigner
        return verifyV1Signature(apkPath, false);
    }

    /**
     * @return the verity root hash in the Signing Block.
     */
    public static byte[] getVerityRootHash(String apkPath)
            throws IOException, SignatureNotFoundException, SecurityException {
        // first try v3
        try {
            return ApkSignatureSchemeV3Verifier.getVerityRootHash(apkPath);
        } catch (SignatureNotFoundException e) {
            // try older version
        }
        return ApkSignatureSchemeV2Verifier.getVerityRootHash(apkPath);
    }

    /**
     * Generates the Merkle tree and verity metadata to the buffer allocated by the {@code
     * ByteBufferFactory}.
     *
     * @return the verity root hash of the generated Merkle tree.
     */
    public static byte[] generateApkVerity(String apkPath, ByteBufferFactory bufferFactory)
            throws IOException, SignatureNotFoundException, SecurityException, DigestException,
                   NoSuchAlgorithmException {
        // first try v3
        try {
            return ApkSignatureSchemeV3Verifier.generateApkVerity(apkPath, bufferFactory);
        } catch (SignatureNotFoundException e) {
            // try older version
        }
        return ApkSignatureSchemeV2Verifier.generateApkVerity(apkPath, bufferFactory);
    }

    /**
     * Generates the FSVerity root hash from FSVerity header, extensions and Merkle tree root hash
     * in Signing Block.
     *
     * @return FSverity root hash
     */
    public static byte[] generateFsverityRootHash(String apkPath)
            throws NoSuchAlgorithmException, DigestException, IOException {
        // first try v3
        try {
            return ApkSignatureSchemeV3Verifier.generateFsverityRootHash(apkPath);
        } catch (SignatureNotFoundException e) {
            // try older version
        }
        try {
            return ApkSignatureSchemeV2Verifier.generateFsverityRootHash(apkPath);
        } catch (SignatureNotFoundException e) {
            return null;
        }
    }

    /**
     * Result of a successful APK verification operation.
     */
    public static class Result {
        public final Certificate[][] certs;
        public final Signature[] sigs;
        public final int signatureSchemeVersion;

        public Result(Certificate[][] certs, Signature[] sigs, int signingVersion) {
            this.certs = certs;
            this.sigs = sigs;
            this.signatureSchemeVersion = signingVersion;
        }
    }
}
