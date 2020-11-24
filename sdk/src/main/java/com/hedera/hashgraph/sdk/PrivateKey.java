package com.hedera.hashgraph.sdk;

import com.google.errorprone.annotations.Var;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.bouncycastle.util.encoders.Hex;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A private key on the Hedera™ network.
 */
public final class PrivateKey extends Key {
    private final byte[] keyData;

    @Nullable
    private final KeyParameter chainCode;

    // Cache the derivation of the public key
    @Nullable
    private PublicKey publicKey;

    PrivateKey(byte[] keyData, @Nullable KeyParameter chainCode) {
        this.keyData = keyData;
        this.chainCode = chainCode;
    }

    /**
     * Generates a new <a href="https://ed25519.cr.yp.to/">Ed25519</a> private key.
     *
     * @return the new Ed25519 private key.
     */
    public static PrivateKey generate() {
        // extra 32 bytes for chain code
        byte[] data = new byte[Ed25519.SECRET_KEY_SIZE + 32];
        ThreadLocalSecureRandom.current().nextBytes(data);

        return derivableKey(data, false);
    }

    /**
     * Recover a private key from a generated mnemonic phrase and a passphrase.
     * <p>
     * This is not compatible with the phrases generated by the Android and iOS wallets;
     * use the no-passphrase version instead.
     *
     * @param mnemonic   the mnemonic phrase which should be a 24 byte list of words.
     * @param passphrase the passphrase used to protect the mnemonic (not used in the
     *                   mobile wallets, use {@link #fromMnemonic(Mnemonic)} instead.)
     * @return the recovered key; use {@link #derive(int)} to get a key for an account index (0
     * for default account)
     */
    public static PrivateKey fromMnemonic(Mnemonic mnemonic, String passphrase) {
        var seed = mnemonic.toSeed(passphrase);

        var hmacSha512 = new HMac(new SHA512Digest());
        hmacSha512.init(new KeyParameter("ed25519 seed".getBytes(StandardCharsets.UTF_8)));
        hmacSha512.update(seed, 0, seed.length);

        var derivedState = new byte[hmacSha512.getMacSize()];
        hmacSha512.doFinal(derivedState, 0);

        @Var var derivedKey = derivableKey(derivedState, false);

        // BIP-44 path with the Hedera Hbar coin-type (omitting key index)
        // we pre-derive most of the path as the mobile wallets don't expose more than the index
        // https://github.com/bitcoin/bips/blob/master/bip-0044.mediawiki
        // https://github.com/satoshilabs/slips/blob/master/slip-0044.md
        for (int index : new int[]{44, 3030, 0, 0}) {
            derivedKey = derivedKey.derive(index);
        }

        return derivedKey;
    }
    /**
     * Recover a private key from a mnemonic phrase compatible with the iOS and Android wallets.
     * <p>
     * An overload of {@link #fromMnemonic(Mnemonic, String)} which uses an empty string for the
     * passphrase.
     *
     * @param mnemonic the mnemonic phrase which should be a 24 byte list of words.
     * @return the recovered key; use {@link #derive(int)} to get a key for an account index (0
     * for default account)
     */
    public static PrivateKey fromMnemonic(Mnemonic mnemonic) {
        return fromMnemonic(mnemonic, "");
    }

    public static PrivateKey fromString(String privateKey) {
        return fromBytes(Hex.decode(privateKey));
    }

    public static PrivateKey fromBytes(byte[] privateKey) {
        if ((privateKey.length == Ed25519.SECRET_KEY_SIZE)
            || (privateKey.length == Ed25519.SECRET_KEY_SIZE + Ed25519.PUBLIC_KEY_SIZE)) {
            // If this is a 32 or 64 byte string, assume an Ed25519 private key
            return new PrivateKey(Arrays.copyOfRange(privateKey, 0, Ed25519.SECRET_KEY_SIZE), null);
        }

        // Assume a DER-encoded private key descriptor
        return PrivateKey.fromPrivateKeyInfo(PrivateKeyInfo.getInstance(privateKey));
    }

    private static PrivateKey fromPrivateKeyInfo(PrivateKeyInfo privateKeyInfo) {
        try {
            var privateKey = (ASN1OctetString) privateKeyInfo.parsePrivateKey();

            return new PrivateKey(privateKey.getOctets(), null);
        } catch (IOException e) {
            throw new BadKeyException(e);
        }
    }

    private static PrivateKey derivableKey(byte[] deriveData, boolean isLegacy) {
        var keyData = Arrays.copyOfRange(deriveData, 0, 32);
        var chainCode = new KeyParameter(deriveData, 32, 32);

        if(!isLegacy){
            return new PrivateKey(keyData, chainCode);
        }
        else{
            return new PrivateKey(keyData, null);
        }
    }

    /**
     * Parse a private key from a PEM encoded reader.
     * <p>
     * This will read the first "PRIVATE KEY" section in the stream as an Ed25519 private key.
     *
     * @throws IOException     if one occurred while reading.
     * @throws BadKeyException if no "PRIVATE KEY" section was found or the key was not an Ed25519
     *                         private key.
     * @param pemFile The Reader containing the pem file
     * @return {@link com.hedera.hashgraph.sdk.PrivateKey}
     */
    public static PrivateKey readPem(Reader pemFile) throws IOException {
        return readPem(pemFile, null);
    }

    /**
     * Parse a private key from a PEM encoded stream. The key may be encrypted, e.g. if it was
     * generated by OpenSSL.
     * <p>
     * If <i>password</i> is not null or empty, this will read the first "ENCRYPTED PRIVATE KEY"
     * section in the stream as a PKCS#8
     * <a href="https://tools.ietf.org/html/rfc5208#page-4">EncryptedPrivateKeyInfo</a> structure
     * and use that algorithm to decrypt the private key with the given password. Otherwise,
     * it will read the first "PRIVATE KEY" section as DER-encoded Ed25519 private key.
     * <p>
     * To generate an encrypted private key with OpenSSL, open a terminal and enter the following
     * command:
     * <pre>
     * {@code openssl genpkey -algorithm ed25519 -aes-128-cbc > key.pem}
     * </pre>
     * <p>
     * Then enter your password of choice when prompted. When the command completes, your encrypted
     * key will be saved as `key.pem` in the working directory of your terminal.
     *
     * @param pemFile  the PEM encoded file
     * @param password the password to decrypt the PEM file; if null or empty, no decryption is performed.
     * @throws IOException     if one occurred while reading the PEM file
     * @throws BadKeyException if no "ENCRYPTED PRIVATE KEY" or "PRIVATE KEY" section was found,
     *                         if the passphrase is wrong or the key was not an Ed25519 private key.
     * @return {@link com.hedera.hashgraph.sdk.PrivateKey}
     */
    public static PrivateKey readPem(Reader pemFile, @Nullable String password) throws IOException {
        return fromPrivateKeyInfo(Pem.readPrivateKey(pemFile, password));
    }

    /**
     * Parse a private key from a PEM encoded string.
     *
     * @throws IOException     if the PEM string was improperly encoded
     * @throws BadKeyException if no "PRIVATE KEY" section was found or the key was not an Ed25519
     *                         private key.
     * @see #readPem(Reader)
     * @param pemEncoded The String containing the pem
     * @return {@link com.hedera.hashgraph.sdk.PrivateKey}
     */
    public static PrivateKey fromPem(String pemEncoded) throws IOException {
        return readPem(new StringReader(pemEncoded));
    }

    /**
     * Parse a private key from a PEM encoded string.
     * <p>
     * The private key may be encrypted, e.g. if it was generated by OpenSSL.
     *
     * @param encodedPem the encoded PEM string
     * @param password   the password to decrypt the PEM file; if null or empty, no decryption is performed.
     * @throws IOException     if the PEM string was improperly encoded
     * @throws BadKeyException if no "ENCRYPTED PRIVATE KEY" or "PRIVATE KEY" section was found,
     *                         if the passphrase is wrong or the key was not an Ed25519 private key.
     * @see #readPem(Reader, String)
     * @return {@link com.hedera.hashgraph.sdk.PrivateKey}
     */
    public static PrivateKey fromPem(String encodedPem, @Nullable String password) throws IOException {
        return readPem(new StringReader(encodedPem), password);
    }

    /**
     * Check if this private key supports derivation.
     * <p>
     * This is currently only the case if this private key was created from a mnemonic.
     *
     * @return boolean
     */
    public boolean isDerivable() {
        return this.chainCode != null;
    }

    /**
     * Given a wallet/account index, derive a child key compatible with the iOS and Android wallets.
     * <p>
     * Use index 0 for the default account.
     *
     * @param index the wallet/account index of the account, 0 for the default account.
     * @return the derived key
     * @throws IllegalStateException if this key does not support derivation.
     * @see #isDerivable()
     */
    public PrivateKey derive(int index) {
        if (this.chainCode == null) {
            throw new IllegalStateException("this private key does not support derivation");
        }

        // SLIP-10 child key derivation
        // https://github.com/satoshilabs/slips/blob/master/slip-0010.md#master-key-generation
        var hmacSha512 = new HMac(new SHA512Digest());

        hmacSha512.init(chainCode);
        hmacSha512.update((byte) 0);

        hmacSha512.update(keyData, 0, Ed25519.SECRET_KEY_SIZE);

        // write the index in big-endian order, setting the 31st bit to mark it "hardened"
        var indexBytes = new byte[4];
        ByteBuffer.wrap(indexBytes).order(ByteOrder.BIG_ENDIAN).putInt(index);
        indexBytes[0] |= (byte) 0b10000000;

        hmacSha512.update(indexBytes, 0, indexBytes.length);

        var output = new byte[64];
        hmacSha512.doFinal(output, 0);

        return derivableKey(output, false);
    }

    /**
     * Derive a public key from this private key.
     *
     * <p>The public key can be freely given and used by other parties to verify the signatures
     * generated by this private key.
     *
     * @return the corresponding public key for this private key.
     */
    public PublicKey getPublicKey() {
        if (publicKey != null) {
            return publicKey;
        }

        byte[] publicKeyData = new byte[Ed25519.PUBLIC_KEY_SIZE];
        Ed25519.generatePublicKey(keyData, 0, publicKeyData, 0);

        publicKey = new PublicKey(publicKeyData);
        return publicKey;
    }

    /**
     * Sign a message with this private key.
     *
     * @return the signature of the message.
     * @param message The array of bytes to sign with
     */
    public byte[] sign(byte[] message) {
        byte[] signature = new byte[Ed25519.SIGNATURE_SIZE];
        Ed25519.sign(keyData, 0, message, 0, message.length, signature, 0);

        return signature;
    }

    public byte[] signTransaction(Transaction transaction) {
        transaction.requireExactNode();

        if (!transaction.isFrozen()) {
            transaction.freeze();
        }

        var builder = (com.hedera.hashgraph.sdk.proto.SignedTransaction.Builder) transaction.signedTransactions.get(0);
        var signature = sign(builder.getBodyBytes().toByteArray());

        transaction.addSignature(getPublicKey(), signature);

        return signature;
    }

    @Override
    public byte[] toBytes() {
        return keyData;
    }

    private byte[] toDER() {
        try {
            return new PrivateKeyInfo(
                new AlgorithmIdentifier(ID_ED25519),
                new DEROctetString(keyData)
            ).getEncoded("DER");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return Hex.toHexString(toDER());
    }

    @Override
    com.hedera.hashgraph.sdk.proto.Key toKeyProtobuf() {
        // Forward to the corresponding public key.
        return getPublicKey().toKeyProtobuf();
    }

    public static PrivateKey fromLegacyMnemonic(byte[] entropy) {
        byte[] seed = new byte[entropy.length + 8];
        Arrays.fill(seed, entropy.length, entropy.length + 8, (byte)-1);
        System.arraycopy(entropy, 0, seed, 0, entropy.length);

        byte[] salt = new byte[1];
        salt[0] = -1;
        PKCS5S2ParametersGenerator pbkdf2 = new PKCS5S2ParametersGenerator(new SHA512Digest());
        pbkdf2.init(
            seed,
            salt,
            2048);

        KeyParameter key = (KeyParameter) pbkdf2.generateDerivedParameters(256);
        return derivableKey(key.getKey(), true);
    }
}
