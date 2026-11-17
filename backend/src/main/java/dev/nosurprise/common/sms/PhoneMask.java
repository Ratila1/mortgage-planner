package dev.nosurprise.common.sms;

/** Маскировка телефона для логов (§5): видны только последние две цифры. */
public final class PhoneMask {

    private PhoneMask() {
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() < 4) {
            return "***";
        }
        return "*".repeat(phone.length() - 2) + phone.substring(phone.length() - 2);
    }
}
