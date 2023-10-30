
package eu.nebulous.resource.discovery.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tomcat.util.codec.binary.Base64;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Map;

/*
 * SEE:
 *   https://www.baeldung.com/java-aes-encryption-decryption
 *   https://stackoverflow.com/questions/6538485/java-using-aes-256-and-128-symmetric-key-encryption
 */

@Slf4j
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
@RequiredArgsConstructor
public class EncryptionUtil implements InitializingBean {
    public final static String KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA256";
    public final static String KEY_GEN_ALGORITHM = "AES";
    public final static String CIPHER_ALGORITHM = "AES";
    //public final static String CIPHER_ALGORITHM = "AES/CTR/PKCS5Padding";
    public final static int SIZE = 256;
    public final static int ITERATION_COUNT = 65536;

    public final static byte SEPARATOR = 32;
    public final static byte NULL = 0;
    public final static char NULL_CHAR = '\0';

    private final ResourceDiscoveryProperties properties;
    private final ObjectMapper objectMapper;
    private SecretKey key;

    @Override
    public void afterPropertiesSet() throws Exception {
        if (properties.isEnableEncryption())
            initializeSymmetricKey();
    }

    private void initializeSymmetricKey() throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        if (properties.isUsePasswordGeneratedKey()) {
            char[] password;
            byte[] salt;
            if (StringUtils.isNotBlank(properties.getKeyPasswordFile())) {
                // Read key password from file
                File file = Paths.get(properties.getKeyPasswordFile()).toFile();
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] bytes = in.readAllBytes();
                    // find separator
                    int ii = Arrays.binarySearch(bytes, SEPARATOR);
                    password = new char[ii/2];
                    for (int j=0, k=0; j<bytes.length; j+=2, k++)
                        password[k] = (char)((bytes[j]<<8) | (bytes[j+1] & 255));
                    salt = Arrays.copyOfRange(bytes, ii+1, bytes.length);
                    // Clear bytes
                    Arrays.fill(bytes, NULL);
                }
            } else {
                // Read key password from properties
                password = properties.getSymmetricKeyPassword();
                salt = properties.getSalt();
            }

            // Generate key from password
            key = getKeyFromPassword(password, salt);

            // Clear key password from properties and variables
            Arrays.fill(properties.getSymmetricKeyPassword(), NULL_CHAR);
            Arrays.fill(properties.getSalt(), NULL);
            Arrays.fill(password, NULL_CHAR);
            Arrays.fill(salt, NULL);
        } else {
            // Generate new key
            key = generateKey();
            if (StringUtils.isNotBlank(properties.getGeneratedKeyFile())) {
                // Write generated key to file
                File file = Paths.get(properties.getGeneratedKeyFile()).toFile();
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(key.getAlgorithm().getBytes(StandardCharsets.UTF_8));
                    out.write(SEPARATOR);  // space
                    out.write(key.getFormat().getBytes(StandardCharsets.UTF_8));
                    out.write(SEPARATOR);  // space
                    out.write(key.getEncoded());
                }
                log.info("EncryptionUtil: Generated key stored in file: {}", file);
            } else {
                // Log generated key
                log.info("EncryptionUtil: Generated key: {} {} {}",
                        key.getAlgorithm(), key.getFormat(), Base64.encodeBase64String(key.getEncoded()));
            }
        }
    }

    public SecretKey generateKey() throws NoSuchAlgorithmException {
        //final SecretKeySpec secretKey = new SecretKeySpec(keyStr.getBytes(StandardCharsets.UTF_8), "AES");
        KeyGenerator generator = KeyGenerator.getInstance(KEY_GEN_ALGORITHM);
        generator.init(SIZE);
        return generator.generateKey();
    }

    public SecretKey getKeyFromPassword(char[] password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException
    {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_FACTORY_ALGORITHM);
        KeySpec spec = new PBEKeySpec(password, salt, ITERATION_COUNT, SIZE);
        return new SecretKeySpec(factory
                .generateSecret(spec).getEncoded(), KEY_GEN_ALGORITHM);
    }

    public void destroyKey() throws DestroyFailedException {
        if (key!=null) {
            key.destroy();
            key = null;
        }
    }

    public String encryptText(@NonNull String message) {
        if (! properties.isEnableEncryption()) return message;
        try {
            return encrypt(message.getBytes(StandardCharsets.UTF_8), key);
        } catch (IllegalBlockSizeException | NoSuchPaddingException | BadPaddingException |
                 NoSuchAlgorithmException | InvalidKeyException e)
        {
            log.warn("EncryptionUtil: ERROR while encrypting message: ", e);
        }
        return null;
    }

    public String decryptText(@NonNull String message) {
        if (! properties.isEnableEncryption()) return message;
        try {
            return new String(decrypt(message, key), StandardCharsets.UTF_8);
        } catch (NoSuchPaddingException | IllegalBlockSizeException | NoSuchAlgorithmException
                 | BadPaddingException | InvalidKeyException e)
        {
            log.warn("EncryptionUtil: ERROR while decrypting message: ", e);
        }
        return null;
    }

    public String encryptMap(@NonNull Map<?,?> message) {
        try {
            if (! properties.isEnableEncryption()) return objectMapper.writeValueAsString(message);

            byte[] bytes = objectMapper.writeValueAsBytes(message);

            try {
                return encrypt(bytes, key);
            } catch (IllegalBlockSizeException | NoSuchPaddingException | BadPaddingException |
                     NoSuchAlgorithmException | InvalidKeyException e)
            {
                log.warn("EncryptionUtil: ERROR while encrypting message: ", e);
            } finally {
                Arrays.fill(bytes, NULL);
            }
        } catch (JsonProcessingException e) {
            log.warn("EncryptionUtil: ERROR while converting message (Map) to bytes: ", e);
        }
        return null;
    }

    public Map<?, ?> decryptMap(@NonNull String message) {
        try {
            if (! properties.isEnableEncryption()) return objectMapper.readValue(message, Map.class);

            byte[] bytes = decrypt(message, key);

            try {
                TypeReference<Map<String, Object>> tr = new TypeReference<>() {};
                return objectMapper.readValue(bytes, tr);
            } catch (IOException e) {
                log.warn("EncryptionUtil: ERROR while converting decrypted bytes to Map: ", e);
            } finally {
                Arrays.fill(bytes, NULL);
            }
        } catch (NoSuchPaddingException | IllegalBlockSizeException | NoSuchAlgorithmException
                 | BadPaddingException | InvalidKeyException | IOException e)
        {
            log.warn("EncryptionUtil: ERROR while decrypting message: ", e);
        }
        return null;
    }

    private String encrypt(byte[] bytesToEncrypt, SecretKey secretKey)
            throws IllegalBlockSizeException, BadPaddingException, NoSuchPaddingException,
                   NoSuchAlgorithmException, InvalidKeyException
    {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        return Base64.encodeBase64String(
                cipher.doFinal(bytesToEncrypt));
    }

    private byte[] decrypt(final String encryptedMessage, SecretKey secretKey)
            throws NoSuchPaddingException, NoSuchAlgorithmException, IllegalBlockSizeException,
                   BadPaddingException, InvalidKeyException
    {
        //final SecretKeySpec secretKey = new SecretKeySpec(keyStr.getBytes(StandardCharsets.UTF_8), "AES");
        final Cipher c = Cipher.getInstance(CIPHER_ALGORITHM);
        c.init(Cipher.DECRYPT_MODE, secretKey);
        return c.doFinal(
                Base64.decodeBase64(encryptedMessage));
    }
}
