/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.net.ct;

import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.PUBLIC_KEY_INVALID;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.PUBLIC_KEY_NOT_ALLOWED;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.PUBLIC_KEY_NOT_FOUND;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.SIGNATURE_INVALID;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.SIGNATURE_NOT_FOUND;
import static com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState.SIGNATURE_VERIFICATION_FAILED;

import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.connectivity.resources.R;
import com.android.server.connectivity.ConnectivityResources;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Verifier of the log list signature. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class SignatureVerifier {

    private static final String TAG = "SignatureVerifier";

    private final Context mContext;

    @NonNull private Optional<PublicKey> mPublicKey = Optional.empty();

    private final Set<PublicKey> mAllowedKeys = new HashSet<>();

    public SignatureVerifier(Context context) {
        mContext = context;
    }

    void loadAllowedKeys() {
        try (InputStream input =
                new ConnectivityResources(mContext).get().openRawResource(R.raw.ct_public_keys)) {
            mAllowedKeys.addAll(PemReader.readKeysFrom(input));
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Error loading public keys", e);
        }
    }

    void clearAllowedKeys() {
        mAllowedKeys.clear();
    }

    @VisibleForTesting
    Optional<PublicKey> getPublicKey() {
        return mPublicKey;
    }

    void resetPublicKey() {
        mPublicKey = Optional.empty();
    }

    LogListUpdateStatus setPublicKeyFrom(Uri file) throws IOException {
        try (InputStream fileStream = mContext.getContentResolver().openInputStream(file)) {
            return setPublicKey(new String(fileStream.readAllBytes()));
        }
    }

    private LogListUpdateStatus setPublicKey(String publicKey) {
        byte[] decodedPublicKey = null;
        LogListUpdateStatus.Builder statusBuilder = LogListUpdateStatus.builder();

        try {
            decodedPublicKey = Base64.getDecoder().decode(publicKey);
            setPublicKey(
                KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(decodedPublicKey)));
        } catch (IllegalArgumentException e) {
            statusBuilder.setState(PUBLIC_KEY_INVALID);
            Log.w(TAG, "Invalid public key base64 encoding", e);
        } catch (GeneralSecurityException e) {
            statusBuilder.setState(PUBLIC_KEY_NOT_ALLOWED);
            Log.e(TAG, "Public key not in allowlist", e);
        }

        return statusBuilder.build();
    }

    @VisibleForTesting
    void setPublicKey(PublicKey publicKey) throws GeneralSecurityException {
        if (!mAllowedKeys.contains(publicKey)) {
            throw new GeneralSecurityException("Public key not in allowlist");
        }
        mPublicKey = Optional.of(publicKey);
    }

    LogListUpdateStatus verify(Uri file, Uri signature) {
        LogListUpdateStatus.Builder statusBuilder = LogListUpdateStatus.builder();

        if (!mPublicKey.isPresent()) {
            statusBuilder.setState(PUBLIC_KEY_NOT_FOUND);
            Log.e(TAG, "No public key found for log list verification");
            return statusBuilder.build();
        }

        ContentResolver contentResolver = mContext.getContentResolver();

        try (InputStream fileStream = contentResolver.openInputStream(file);
                InputStream signatureStream = contentResolver.openInputStream(signature)) {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(mPublicKey.get());
            verifier.update(fileStream.readAllBytes());

            byte[] signatureBytes = signatureStream.readAllBytes();
            statusBuilder.setSignature(new String(signatureBytes));

            if (!verifier.verify(Base64.getDecoder().decode(signatureBytes))) {
                // Leave the UpdateState as UNKNOWN_STATE if successful as there are other
                // potential failures past the signature verification step
                statusBuilder.setState(SIGNATURE_VERIFICATION_FAILED);
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid signature base64 encoding", e);
            statusBuilder.setState(SIGNATURE_INVALID);
            return statusBuilder.build();
        } catch (InvalidKeyException e) {
            Log.e(TAG, "Key invalid for log list verification", e);
            statusBuilder.setState(SIGNATURE_INVALID);
            return statusBuilder.build();
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "Could not verify new log list", e);
            statusBuilder.setState(SIGNATURE_VERIFICATION_FAILED);
            return statusBuilder.build();
        }

        // Double check if the signature is empty that we set the state correctly
        if (!statusBuilder.build().hasSignature()) {
            statusBuilder.setState(SIGNATURE_NOT_FOUND);
        }

        return statusBuilder.build();
    }

    @VisibleForTesting
    boolean addAllowedKey(PublicKey publicKey) {
        return mAllowedKeys.add(publicKey);
    }
}
