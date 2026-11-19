package dev.nosurprise.internal.web;

import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.internal.web.dto.UserByPhoneResponse;
import dev.nosurprise.loan.signing.SigningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Internal API consumed by sms-verification-service; protected by X-Service-Token. */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private final CustomerService customers;
    private final SigningService signing;

    public InternalController(CustomerService customers, SigningService signing) {
        this.customers = customers;
        this.signing = signing;
    }

    @GetMapping("/users/by-phone")
    public ResponseEntity<UserByPhoneResponse> findUserByPhone(@RequestParam String phone) {
        return customers.findUserIdByPhone(phone)
                .map(id -> ResponseEntity.ok(new UserByPhoneResponse(id)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/verifications/{id}/complete")
    public ResponseEntity<Void> completeVerification(@PathVariable UUID id) {
        signing.completeTelegramVerification(id);
        return ResponseEntity.ok().build();
    }
}
