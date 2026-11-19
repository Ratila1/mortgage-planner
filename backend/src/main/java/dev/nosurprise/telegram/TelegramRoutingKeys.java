package dev.nosurprise.telegram;

/** Routing keys shared with the verification service through domain.events. */
public final class TelegramRoutingKeys {

    public static final String VERIFICATION_REQUESTED = "verification.requested";
    public static final String USER_REGISTERED = "user.registered";
    public static final String USER_PHONE_CHANGED = "user.phone.changed";
    public static final String USER_BLOCKED = "user.blocked";
    public static final String USER_UNBLOCKED = "user.unblocked";

    private TelegramRoutingKeys() {}
}
