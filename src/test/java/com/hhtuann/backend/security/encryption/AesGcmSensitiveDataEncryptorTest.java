package com.hhtuann.backend.security.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AesGcmSensitiveDataEncryptor}.
 */
class AesGcmSensitiveDataEncryptorTest {

    private SensitiveDataEncryptor encryptor;

    /**
     * @return Base64 of exactly 32 zero bytes — a valid 256-bit test key.
     */
    private static String validKeyBase64() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @BeforeEach
    void setUp() {
        encryptor = new AesGcmSensitiveDataEncryptor(validKeyBase64());
    }

    @Test
    void roundTripPlainAscii() {
        String plaintext = "+84123456789";

        String ciphertext = encryptor.encrypt(plaintext);
        String decrypted = encryptor.decrypt(ciphertext);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void roundTripVietnameseUnicode() {
        String plaintext = "Hồ Hoàng Tuấn — Số Căn Cước 0123456789";

        String ciphertext = encryptor.encrypt(plaintext);
        String decrypted = encryptor.decrypt(ciphertext);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void roundTripEmptyString() {
        String ciphertext = encryptor.encrypt("");
        String decrypted = encryptor.decrypt(ciphertext);

        assertThat(decrypted).isEmpty();
        assertThat(ciphertext).isNotEmpty();
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        String plaintext = "0123456789";

        String first = encryptor.encrypt(plaintext);
        String second = encryptor.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        // Both must still decrypt back to the original.
        assertThat(encryptor.decrypt(first)).isEqualTo(plaintext);
        assertThat(encryptor.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void ciphertextCarriesVersionPrefixAndHidesPlaintext() {
        String plaintext = "secret-phone-number";

        String ciphertext = encryptor.encrypt(plaintext);

        assertThat(ciphertext).startsWith("v1:");
        assertThat(ciphertext).doesNotContain(plaintext);
    }

    @Test
    void decryptRejectsUnsupportedVersionPrefix() {
        assertThatThrownBy(() -> encryptor.decrypt("v2:abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsMissingPrefix() {
        assertThatThrownBy(() -> encryptor.decrypt("AAAAAAAAAAAA"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        String ciphertext = encryptor.encrypt("sensitive-value");

        // Flip one character in the Base64URL body, after the "v1:" prefix.
        char[] chars = ciphertext.toCharArray();
        int bodyIndex = 3;
        chars[bodyIndex] = (chars[bodyIndex] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorFailsFastWhenKeyHasWrongLength() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new AesGcmSensitiveDataEncryptor(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void constructorFailsFastWhenKeyIsNotBase64() {
        assertThatThrownBy(() -> new AesGcmSensitiveDataEncryptor("not!!valid!!base64!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encryptRejectsNullPlaintext() {
        assertThatThrownBy(() -> encryptor.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsNullCiphertext() {
        assertThatThrownBy(() -> encryptor.decrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
