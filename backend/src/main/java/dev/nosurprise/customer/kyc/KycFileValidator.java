package dev.nosurprise.customer.kyc;

/**
 * Проверка загружаемого фото паспорта (MICROLOANS.md §5). Тип определяется по сигнатуре файла
 * (magic bytes), а не по заголовку Content-Type: заголовок задаёт клиент и легко подделывается.
 */
public final class KycFileValidator {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private KycFileValidator() {
    }

    /** Возвращает content type по сигнатуре или бросает исключение, если файл пустой, слишком большой или не картинка. */
    public static String detectContentType(byte[] content, long maxBytes) {
        if (content == null || content.length == 0) {
            throw new UnsupportedFileTypeException();
        }
        if (content.length > maxBytes) {
            throw new FileTooLargeException(maxBytes);
        }
        if (startsWith(content, JPEG_SIGNATURE)) {
            return JPEG;
        }
        if (startsWith(content, PNG_SIGNATURE)) {
            return PNG;
        }
        throw new UnsupportedFileTypeException();
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
