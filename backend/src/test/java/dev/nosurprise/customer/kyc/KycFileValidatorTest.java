package dev.nosurprise.customer.kyc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KycFileValidatorTest {

    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

    @Test
    @DisplayName("тип определяется по сигнатуре: JPEG и PNG")
    void detectsBySignature() {
        assertThat(KycFileValidator.detectContentType(PNG, 1000)).isEqualTo(KycFileValidator.PNG);
        assertThat(KycFileValidator.detectContentType(JPEG, 1000)).isEqualTo(KycFileValidator.JPEG);
    }

    @Test
    @DisplayName("не картинка и пустой файл → UnsupportedFileTypeException")
    void rejectsNonImages() {
        assertThatThrownBy(() -> KycFileValidator.detectContentType("%PDF-1.7".getBytes(StandardCharsets.UTF_8), 1000))
                .isInstanceOf(UnsupportedFileTypeException.class);
        assertThatThrownBy(() -> KycFileValidator.detectContentType(new byte[0], 1000))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    @Test
    @DisplayName("больше лимита → FileTooLargeException")
    void rejectsTooLarge() {
        assertThatThrownBy(() -> KycFileValidator.detectContentType(PNG, 4))
                .isInstanceOf(FileTooLargeException.class);
    }
}
